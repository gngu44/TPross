package com.tpross.service;

import com.tpross.config.AccountProperties;
import com.tpross.dto.AccountResponse;
import com.tpross.exception.ResourceNotFoundException;
import com.tpross.model.Account;
import com.tpross.model.User;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final UserRepository users;
    private final AccountProperties properties;

    public AccountService(AccountRepository accounts, UserRepository users, AccountProperties properties) {
        this.accounts = accounts;
        this.users = users;
        this.properties = properties;
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

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getUser().getId(),
                account.getBalance(), account.getCreatedAt());
    }
}
