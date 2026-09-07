package com.tpross.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(Long id, Long userId, BigDecimal balance, Instant createdAt) {
}
