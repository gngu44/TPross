package com.tpross.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpross.dto.CreateTransferRequest;
import com.tpross.exception.TransferConflictException;
import com.tpross.model.Account;
import com.tpross.model.Transaction;
import com.tpross.model.User;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.TransactionRepository;
import com.tpross.repository.UserRepository;
import com.tpross.service.TransferService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@AutoConfigureMockMvc
@Testcontainers
class TransferApiIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private UserRepository users;
    @Autowired
    private TransferService transfers;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private TransactionRepository transactions;

    @BeforeEach
    void clearDisposableDatabase() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from accounts");
        jdbc.update("delete from users");
    }

    @Test
    void transfersBetweenDifferentUsersAndPersistsResponse() throws Exception {
        Account source = account("100.10");
        Account destination = account("20.20");
        String body = mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(source, destination, "25.05")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAccountId").value(source.getId()))
                .andExpect(jsonPath("$.destinationAccountId").value(destination.getId()))
                .andExpect(jsonPath("$.amount").value(25.05))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = mapper.readTree(body);
        long id = response.get("transactionId").asLong();
        assertThat(transactions.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select source_account_id from transactions where id = ?", Long.class, id))
                .isEqualTo(source.getId());
        assertThat(jdbc.queryForObject("select destination_account_id from transactions where id = ?", Long.class, id))
                .isEqualTo(destination.getId());
        assertThat(jdbc.queryForObject("select amount from transactions where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("25.05");
        assertThat(jdbc.queryForObject("select status from transactions where id = ?", String.class, id))
                .isEqualTo("COMPLETED");
        assertBalances(source, "75.05", destination, "45.25");
    }

    @Test
    void permitsSpendingTheExactBalanceWithSourceIdHigherThanDestination() throws Exception {
        Account destination = account("0.00");
        Account source = account("0.01");
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(source, destination, "0.01")))
                .andExpect(status().isCreated());
        assertBalances(source, "0.00", destination, "0.01");
    }

    @Test
    void insufficientFundsChangesNothing() throws Exception {
        Account source = account("10.00");
        Account destination = account("5.00");
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(source, destination, "10.01")))
                .andExpect(status().isConflict());
        assertBalances(source, "10.00", destination, "5.00");
        assertThat(transactions.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eitherMissingAccountChangesNothing(boolean missingSource) throws Exception {
        Account existing = account("10.00");
        CreateTransferRequest request = new CreateTransferRequest(missingSource ? Long.MAX_VALUE : existing.getId(),
                missingSource ? existing.getId() : Long.MAX_VALUE, new BigDecimal("1.00"));
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
        assertThat(balance(existing)).isEqualByComparingTo("10.00");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void sameAccountIsRejectedWithoutChanges() throws Exception {
        Account account = account("10.00");
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(account, account, "1.00")))
                .andExpect(status().isBadRequest());
        assertThat(balance(account)).isEqualByComparingTo("10.00");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void destinationOverflowIsRejectedWithoutDebitingSource() throws Exception {
        Account source = account("1.00");
        Account destination = account("99999999999999999.99");
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(source, destination, "0.01")))
                .andExpect(status().isConflict());
        assertBalances(source, "1.00", destination, "99999999999999999.99");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void serviceAlsoValidatesCallsOutsideTheController() {
        Account source = account("10.00");
        Account destination = account("0.00");
        assertThatThrownBy(() -> transfers.createTransfer(
                new CreateTransferRequest(source.getId(), destination.getId(), new BigDecimal("-1.00"))))
                .isInstanceOf(ConstraintViolationException.class);
        assertBalances(source, "10.00", destination, "0.00");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void runtimeFailureAfterSqlFlushRollsBackBothBalancesAndTransaction() throws Exception {
        Account source = account("100.00");
        Account destination = account("20.00");
        doAnswer(invocation -> {
            Transaction saved = (Transaction) invocation.callRealMethod();
            // The real INSERT and both UPDATEs have reached PostgreSQL in the open transaction.
            assertBalances(source, "75.00", destination, "45.00");
            assertThat(jdbc.queryForObject("select count(*) from transactions", Long.class)).isEqualTo(1);
            throw new IllegalStateException("Simulated failure after SQL flush, transaction " + saved.getId());
        }).when(transactions).saveAndFlush(any(Transaction.class));

        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(source, destination, "25.00")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
        // These reads happen after the request transaction has rolled back.
        assertBalances(source, "100.00", destination, "20.00");
        assertThat(transactions.count()).isZero();
    }

    @Test
    void simultaneousDebitsCannotSpendTheSameFundsTwice() throws Exception {
        Account source = account("100.00");
        Account firstDestination = account("0.00");
        Account secondDestination = account("0.00");
        List<Integer> results = concurrentTransfers(source, request(source, firstDestination, "80.00"),
                request(source, secondDestination, "80.00"));
        assertThat(results).containsExactlyInAnyOrder(201, 409);
        assertThat(balance(source)).isEqualByComparingTo("20.00");
        assertThat(balance(firstDestination).add(balance(secondDestination))).isEqualByComparingTo("80.00");
        assertThat(transactions.count()).isEqualTo(1);
    }

    @Test
    void simultaneousCreditsDoNotLoseAnUpdate() throws Exception {
        Account destination = account("0.00");
        Account firstSource = account("100.00");
        Account secondSource = account("100.00");
        assertThat(concurrentTransfers(destination, request(firstSource, destination, "80.00"),
                request(secondSource, destination, "80.00"))).containsExactly(201, 201);
        assertBalances(firstSource, "20.00", secondSource, "20.00");
        assertThat(balance(destination)).isEqualByComparingTo("160.00");
        assertThat(transactions.count()).isEqualTo(2);
    }

    @Test
    void oppositeDirectionTransfersCompleteWithoutDeadlocking() throws Exception {
        Account first = account("100.00");
        Account second = account("100.00");
        assertThat(concurrentTransfers(first, request(first, second, "10.00"),
                request(second, first, "20.00"))).containsExactly(201, 201);
        assertBalances(first, "110.00", second, "90.00");
        assertThat(transactions.count()).isEqualTo(2);
    }

    private List<Integer> concurrentTransfers(Account lock, CreateTransferRequest first,
            CreateTransferRequest second) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Integer>> futures = new TransactionTemplate(transactionManager).execute(status -> {
                accounts.findByIdForUpdate(lock.getId()).orElseThrow();
                List<Future<Integer>> pending = List.of(executor.submit(transferTask(first)),
                        executor.submit(transferTask(second)));
                // Prove both operations actually contend for row locks before releasing the blocker.
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject("""
                        select count(*) from pg_stat_activity
                        where datname = current_database() and wait_event_type = 'Lock' and state = 'active'
                        """, Long.class)).isGreaterThanOrEqualTo(2));
                return pending;
            });
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    private Callable<Integer> transferTask(CreateTransferRequest request) {
        return () -> {
            try {
                transfers.createTransfer(request);
                return 201;
            } catch (TransferConflictException exception) {
                return 409;
            }
        };
    }

    private Account account(String balance) {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "stored-hash"));
        Account account = new Account(user);
        account.setBalance(new BigDecimal(balance));
        return accounts.saveAndFlush(account);
    }

    private CreateTransferRequest request(Account source, Account destination, String amount) {
        return new CreateTransferRequest(source.getId(), destination.getId(), new BigDecimal(amount));
    }

    private String json(Account source, Account destination, String amount) throws Exception {
        return mapper.writeValueAsString(request(source, destination, amount));
    }

    private BigDecimal balance(Account account) {
        return jdbc.queryForObject("select balance from accounts where id = ?", BigDecimal.class, account.getId());
    }

    private void assertBalances(Account source, String sourceBalance, Account destination, String destinationBalance) {
        assertThat(balance(source)).isEqualByComparingTo(sourceBalance);
        assertThat(balance(destination)).isEqualByComparingTo(destinationBalance);
    }
}
