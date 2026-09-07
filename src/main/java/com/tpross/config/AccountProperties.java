package com.tpross.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "app.accounts")
public record AccountProperties(
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal startingBalance
) {
}
