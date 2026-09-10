package com.tpross.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AccountPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ApplicationConfig.class);

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "0.001", "100000000000000000.00", "not-a-number"})
    void refusesInvalidStartingBalances(String balance) {
        contextRunner.withPropertyValues("app.accounts.starting-balance=" + balance)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void defaultsToZeroOutsideLocalProfile() {
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AccountProperties.class).startingBalance()).isEqualByComparingTo("0.00");
                });
    }

    @Test
    void localProfileReadsDevelopmentBalanceOverride() {
        contextRunner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.profiles.active=local", "DEV_STARTING_BALANCE=765.43")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AccountProperties.class).startingBalance()).isEqualByComparingTo("765.43");
                });
    }
}
