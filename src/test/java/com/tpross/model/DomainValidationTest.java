package com.tpross.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DomainValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-an-email"})
    void rejectsInvalidEmail(String email) {
        assertInvalidProperty(new User(email, "already-hashed-password"), "email");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void rejectsMissingPasswordHash(String passwordHash) {
        assertInvalidProperty(new User("owner@example.com", passwordHash), "passwordHash");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.001", "100000000000000000.00"})
    void rejectsInvalidBalance(String balance) {
        Account account = new Account(new User("owner@example.com", "hash"));
        account.setBalance(new BigDecimal(balance));
        assertInvalidProperty(account, "balance");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "-0.01", "1.001", "100000000000000000.00"})
    void rejectsInvalidAmount(String amount) {
        User user = new User("owner@example.com", "hash");
        Transaction transaction = new Transaction(new Account(user), new Account(user), new BigDecimal(amount));
        assertInvalidProperty(transaction, "amount");
    }

    @Test
    void acceptsExactMoneyAtThePrecisionLimit() {
        User user = new User("owner@example.com", "hash");
        Account source = new Account(user);
        source.setBalance(new BigDecimal("99999999999999999.99"));
        assertThat(validator.validate(source)).isEmpty();
        assertThat(validator.validate(new Transaction(source, new Account(user), new BigDecimal("0.01"))))
                .isEmpty();
    }

    @Test
    void requiresOwnerBothAccountsAmountAndStatus() {
        assertInvalidProperty(new Account(null), "user");
        Transaction transaction = new Transaction(null, null, null);
        transaction.setStatus(null);
        assertInvalidProperty(transaction, "sourceAccount");
        assertInvalidProperty(transaction, "destinationAccount");
        assertInvalidProperty(transaction, "amount");
        assertInvalidProperty(transaction, "status");
    }

    @Test
    void passwordHashIsNotSerialized() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new User("owner@example.com", "private-hash"));
        assertThat(json).contains("owner@example.com").doesNotContain("passwordHash", "private-hash");
    }

    private void assertInvalidProperty(Object entity, String property) {
        assertThat(validator.validate(entity))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(property));
    }
}
