package com.tpross.controller;

import com.tpross.dto.AccountResponse;
import com.tpross.dto.CreateAccountRequest;
import com.tpross.dto.TransactionHistoryResponse;
import com.tpross.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/api/users/{userId}/accounts")
    public ResponseEntity<AccountResponse> createAccount(@PathVariable @Positive Long userId,
            @Valid @RequestBody(required = false) CreateAccountRequest request) {
        AccountResponse response = accounts.createAccount(userId);
        return ResponseEntity.created(URI.create("/api/accounts/" + response.id())).body(response);
    }

    @GetMapping("/api/accounts/{accountId}")
    public AccountResponse getAccount(@PathVariable @Positive Long accountId) {
        return accounts.getAccount(accountId);
    }

    @GetMapping("/api/accounts/{accountId}/transactions")
    public TransactionHistoryResponse getTransactions(@PathVariable @Positive Long accountId) {
        return accounts.getTransactions(accountId);
    }
}
