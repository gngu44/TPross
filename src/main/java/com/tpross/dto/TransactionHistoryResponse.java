package com.tpross.dto;

import java.util.List;

public record TransactionHistoryResponse(List<TransferResponse> transactions) {
}
