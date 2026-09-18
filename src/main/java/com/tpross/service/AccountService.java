package com.tpross.service;

import com.tpross.config.AccountProperties;
import com.tpross.dto.AccountResponse;
import com.tpross.dto.TransactionHistoryResponse;
import com.tpross.dto.TransferResponse;
import com.tpross.exception.InvalidPaginationException;
import com.tpross.exception.ResourceNotFoundException;
import com.tpross.model.Account;
import com.tpross.model.User;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.TransactionRepository;
import com.tpross.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
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
    public TransactionHistoryResponse getTransactions(Long accountId, int page, int size) {
        // JPA offsets are integers even though Spring Data calculates them as longs.
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new InvalidPaginationException();
        }
        if (!accounts.existsById(accountId)) {
            throw new ResourceNotFoundException("Account", accountId);
        }
        Slice<TransferResponse> history = transactions.findHistoryByAccountId(accountId, PageRequest.of(page, size));
        return new TransactionHistoryResponse(history.getContent(), page, size, history.hasNext());
    }

    private AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getUser().getId(),
                account.getBalance(), account.getCreatedAt());
    }
}
