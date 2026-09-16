package com.tpross.repository;

import com.tpross.model.Account;
import com.tpross.model.Transaction;
import com.tpross.model.TransactionStatus;
import com.tpross.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Container disposal removes the schema; avoid shutdown DDL after PostgreSQL has stopped.
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class DomainPersistenceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private UserRepository users;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void roundTripsEntitiesRelationshipsExactMoneyAndTimestamps() {
        User user = users.saveAndFlush(new User("owner@example.com", "stored-hash"));
        Account source = accounts.saveAndFlush(new Account(user));
        Account destination = accounts.saveAndFlush(new Account(user));
        source.setBalance(new BigDecimal("12345678901234567.89"));
        Transaction saved = transactions.saveAndFlush(
                new Transaction(source, destination, new BigDecimal("0.10")));
        entityManager.clear();

        Transaction loaded = transactions.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getSourceAccount().getId()).isEqualTo(source.getId());
        assertThat(loaded.getDestinationAccount().getId()).isEqualTo(destination.getId());
        assertThat(loaded.getSourceAccount().getUser().getId()).isEqualTo(user.getId());
        assertThat(loaded.getDestinationAccount().getUser().getId()).isEqualTo(user.getId());
        assertThat(loaded.getSourceAccount().getBalance()).isEqualByComparingTo("12345678901234567.89");
        assertThat(loaded.getDestinationAccount().getBalance()).isEqualByComparingTo("0.00");
        assertThat(loaded.getAmount()).isEqualByComparingTo("0.10");
        assertThat(loaded.getStatus()).isEqualTo(TransactionStatus.PENDING);
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getSourceAccount().getCreatedAt()).isNotNull();
        assertThat(users.findById(user.getId()).orElseThrow().getCreatedAt()).isNotNull();

        Instant createdAt = loaded.getCreatedAt();
        loaded.setStatus(TransactionStatus.COMPLETED);
        transactions.flush();
        entityManager.clear();
        assertThat(transactions.findById(saved.getId()).orElseThrow().getCreatedAt()).isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("select status from transactions where id = ?", String.class, saved.getId()))
                .isEqualTo("COMPLETED");
    }

    @Test
    void rejectsDuplicateEmail() {
        users.saveAndFlush(new User("owner@example.com", "hash-one"));
        assertThatThrownBy(() -> users.saveAndFlush(new User("owner@example.com", "hash-two")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRequiresAnAccountOwner() {
        assertThatThrownBy(() -> jdbc.update(
                "insert into accounts (user_id, balance, created_at) values (null, 0.00, now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsNegativeBalanceEvenWithoutBeanValidation() {
        Account account = account();
        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -0.01 where id = ?", account.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-0.01"})
    void databaseRejectsNonpositiveAmount(String amount) {
        Account source = account();
        Account destination = accounts.saveAndFlush(new Account(source.getUser()));
        assertThatThrownBy(() -> insertTransaction(source.getId(), destination.getId(), new BigDecimal(amount), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsTransfersToTheSameAccount() {
        Account account = account();
        assertThatThrownBy(() -> transactions.saveAndFlush(
                new Transaction(account, account, new BigDecimal("1.00"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsMissingReferencedAccounts() {
        Account account = account();
        assertThatThrownBy(() -> insertTransaction(account.getId(), Long.MAX_VALUE, new BigDecimal("1.00"), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownStatus() {
        Account source = account();
        Account destination = accounts.saveAndFlush(new Account(source.getUser()));
        assertThatThrownBy(() -> insertTransaction(source.getId(), destination.getId(), new BigDecimal("1.00"), "UNKNOWN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databasePreventsDeletingUsersWithAccounts() {
        Account account = account();
        assertThatThrownBy(() -> jdbc.update("delete from users where id = ?", account.getUser().getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databasePreventsDeletingAccountsWithTransactions() {
        Account source = account();
        Account destination = accounts.saveAndFlush(new Account(source.getUser()));
        transactions.saveAndFlush(new Transaction(source, destination, new BigDecimal("1.00")));
        assertThatThrownBy(() -> jdbc.update("delete from accounts where id = ?", source.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Account account() {
        return accounts.saveAndFlush(new Account(users.saveAndFlush(new User("owner@example.com", "hash"))));
    }

    private void insertTransaction(Long source, Long destination, BigDecimal amount, String status) {
        jdbc.update("""
                insert into transactions (source_account_id, destination_account_id, amount, status, created_at)
                values (?, ?, ?, ?, now())
                """, source, destination, amount, status);
    }
}
