package com.tpross.dto;

import com.tpross.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(Long transactionId, Long sourceAccountId, Long destinationAccountId,
        BigDecimal amount, TransactionStatus status, Instant timestamp) {
}
