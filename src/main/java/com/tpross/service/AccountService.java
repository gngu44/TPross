package com.tpross.service;

import com.tpross.config.AccountProperties;
import com.tpross.dto.AccountResponse;
import com.tpross.dto.TransactionHistoryResponse;
import com.tpross.dto.TransferResponse;
import com.tpross.exception.ResourceNotFoundException;
import com.tpross.model.Account;
import com.tpross.model.User;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.TransactionRepository;
import com.tpross.repository.UserRepository;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final UserRepository users;
    private final AccountProperties properties;
    private final TransactionRepository transactions;

    public AccountService(AccountRepository accounts, UserRepository users, AccountProperties properties,
            TransactionRepository transactions) {
        this.accounts = accounts;
        this.users = users;
        this.properties = properties;
        this.transactions = transactions;
    }

    @Transactional
    public AccountResponse createAccount(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Account account = new Account(user);
        account.setBalance(properties.startingBalance().setScale(2));
        return toResponse(accounts.saveAndFlush(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long accountId) {
        return toResponse(accounts.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId)));
    }

    @Transactional(readOnly = true)
    public TransactionHistoryResponse getTransactions(Long accountId) {
        if (!accounts.existsById(accountId)) {
            throw new ResourceNotFoundException("Account", accountId);
        }
        List<TransferResponse> history = transactions.findHistoryByAccountId(accountId);
        return new TransactionHistoryResponse(history);
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getUser().getId(),
                account.getBalance(), account.getCreatedAt());
    }
}
