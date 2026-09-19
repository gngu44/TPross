package com.tpross.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tpross.model.Account;
import com.tpross.model.Transaction;
import com.tpross.model.TransactionStatus;
import com.tpross.model.User;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.TransactionRepository;
import com.tpross.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@AutoConfigureMockMvc
@Testcontainers
@Import(AccountTransactionHistoryIT.SqlCaptureConfiguration.class)
class AccountTransactionHistoryIT {

    private static final List<String> SQL = new CopyOnWriteArrayList<>();

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
    private TransactionRepository transactions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeEach
    void clearDisposableDatabase() {
        jdbc.update("delete from transactions");
        jdbc.update("delete from accounts");
        jdbc.update("delete from users");
        resetMeasurements();
    }

    @Test
    void combinesBothDirectionsAndAllStatusesWithDeterministicNewestFirstPagination() throws Exception {
        Account owner = account();
        Account other = account();
        Account unrelated = account();
        long incoming = transaction(other, owner, "10.20", TransactionStatus.FAILED, "2026-09-03T12:00:00Z");
        long outgoing = transaction(owner, other, "2.30", TransactionStatus.PENDING, "2026-09-03T12:00:00Z");
        // A higher ID with an older timestamp must still come last.
        long oldest = transaction(owner, other, "5.10", TransactionStatus.COMPLETED, "2026-09-01T12:00:00Z");
        transaction(other, unrelated, "9.99", TransactionStatus.COMPLETED, "2026-09-04T12:00:00Z");

        JsonNode first = history(owner, 0, 2);
        assertThat(first.get("transactions").size()).isEqualTo(2);
        assertThat(first.get("transactions").get(0).get("transactionId").asLong()).isEqualTo(outgoing);
        assertThat(first.get("transactions").get(1).get("transactionId").asLong()).isEqualTo(incoming);
        assertThat(first.get("transactions").get(0).get("sourceAccountId").asLong()).isEqualTo(owner.getId());
        assertThat(first.get("transactions").get(1).get("destinationAccountId").asLong()).isEqualTo(owner.getId());
        assertThat(first.get("transactions").get(0).get("amount").decimalValue()).isEqualByComparingTo("2.30");
        assertThat(first.get("transactions").get(0).get("timestamp").asText()).isEqualTo("2026-09-03T12:00:00Z");
        assertThat(first.get("transactions").get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(first.get("transactions").get(1).get("status").asText()).isEqualTo("FAILED");
        assertThat(first.get("hasNext").asBoolean()).isTrue();

        JsonNode second = history(owner, 1, 2);
        assertThat(second.get("transactions").size()).isEqualTo(1);
        assertThat(second.get("transactions").get(0).get("transactionId").asLong()).isEqualTo(oldest);
        assertThat(second.get("hasNext").asBoolean()).isFalse();
        assertThat(second.get("page").asInt()).isEqualTo(1);
    }

    @Test
    void defaultsToTwentyRowsWithoutLoadingEntitiesJoiningAccountsOrCountingHistory() throws Exception {
        Account owner = account();
        Account other = account();
        for (int i = 0; i < 21; i++) {
            transaction(owner, other, "1.00", TransactionStatus.COMPLETED, "2026-09-03T12:00:00Z");
        }
        resetMeasurements();
        mvc.perform(get("/api/accounts/" + owner.getId() + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(20))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.transactions[0].sourceAccount").doesNotExist())
                .andExpect(jsonPath("$.transactions[0].destinationAccount").doesNotExist());

        assertThat(statistics().getPrepareStatementCount()).isEqualTo(2); // Existence check + one bounded history query.
        assertThat(statistics().getEntityLoadCount()).isZero();
        assertThat(statistics().getEntityFetchCount()).isZero();
        assertThat(statistics().getCollectionFetchCount()).isZero();
        assertThat(SQL).hasSize(2);
        assertThat(SQL).filteredOn(sql -> sql.contains(" from transactions ")).singleElement()
                .satisfies(sql -> assertThat(sql)
                        .contains("source_account_id", "destination_account_id", "created_at desc", ".id desc", "fetch first")
                        .doesNotContain(" join ", "count(", "password_hash"));

        JsonNode next = history(owner, 1, 20);
        assertThat(next.get("transactions").size()).isEqualTo(1);
        assertThat(next.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void existingAccountWithoutTransactionsReturnsEmptyHistory() throws Exception {
        Account owner = account();
        mvc.perform(get("/api/accounts/" + owner.getId() + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").isEmpty())
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    void pageBeyondAvailableHistoryReturnsEmptySlice() throws Exception {
        Account owner = account();
        transaction(owner, account(), "1.00", TransactionStatus.COMPLETED, "2026-09-03T12:00:00Z");
        JsonNode page = history(owner, 10, 20);
        assertThat(page.get("transactions").isEmpty()).isTrue();
        assertThat(page.get("page").asInt()).isEqualTo(10);
        assertThat(page.get("hasNext").asBoolean()).isFalse();
    }

    @Test
    void missingAccountReturns404WithoutQueryingHistory() throws Exception {
        resetMeasurements();
        mvc.perform(get("/api/accounts/" + Long.MAX_VALUE + "/transactions"))
                .andExpect(status().isNotFound());
        assertThat(statistics().getPrepareStatementCount()).isEqualTo(1);
        assertThat(SQL).noneMatch(sql -> sql.contains(" from transactions "));
    }

    @Test
    void paginationOffsetOverflowReturns400BeforeDatabaseAccess() throws Exception {
        resetMeasurements();
        mvc.perform(get("/api/accounts/1/transactions").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest());
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    private JsonNode history(Account account, int page, int size) throws Exception {
        String body = mvc.perform(get("/api/accounts/" + account.getId() + "/transactions")
                        .param("page", Integer.toString(page)).param("size", Integer.toString(size)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    private Account account() {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "stored-hash"));
        return accounts.saveAndFlush(new Account(user));
    }

    private long transaction(Account source, Account destination, String amount, TransactionStatus status, String timestamp) {
        Transaction transaction = new Transaction(source, destination, new BigDecimal(amount));
        transaction.setStatus(status);
        long id = transactions.saveAndFlush(transaction).getId();
        // Fix fixture timestamps independently of insertion order to exercise both ordering keys.
        jdbc.update("update transactions set created_at = ? where id = ?", Timestamp.from(Instant.parse(timestamp)), id);
        return id;
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    private void resetMeasurements() {
        statistics().clear();
        SQL.clear();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlCaptureConfiguration {
        @Bean
        HibernatePropertiesCustomizer captureSql() {
            return properties -> properties.put("hibernate.session_factory.statement_inspector", (StatementInspector) sql -> {
                SQL.add(sql.toLowerCase(Locale.ROOT));
                return sql;
            });
        }
    }
}
