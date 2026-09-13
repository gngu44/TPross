package com.tpross.service;

import com.tpross.dto.CreateTransferRequest;
import com.tpross.dto.TransferResponse;
import com.tpross.exception.InvalidTransferException;
import com.tpross.exception.ResourceNotFoundException;
import com.tpross.exception.TransferConflictException;
import com.tpross.model.Account;
import com.tpross.model.Transaction;
import com.tpross.model.TransactionStatus;
import com.tpross.repository.AccountRepository;
import com.tpross.repository.TransactionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Service
@Validated
public class TransferService {

    private static final BigDecimal MAX_BALANCE = new BigDecimal("99999999999999999.99");

    private final AccountRepository accounts;
    private final TransactionRepository transactions;

    public TransferService(AccountRepository accounts, TransactionRepository transactions) {
        this.accounts = accounts;
        this.transactions = transactions;
    }

    @Transactional(rollbackFor = Exception.class)
    public TransferResponse createTransfer(@NotNull @Valid CreateTransferRequest request) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw new InvalidTransferException();
        }

        // Every transfer takes locks in the same order, including opposite-direction transfers.
        Account first = lockAccount(Math.min(request.sourceAccountId(), request.destinationAccountId()));
        Account second = lockAccount(Math.max(request.sourceAccountId(), request.destinationAccountId()));
        Account source = first.getId().equals(request.sourceAccountId()) ? first : second;
        Account destination = first.getId().equals(request.destinationAccountId()) ? first : second;
        BigDecimal amount = request.amount().setScale(2);

        if (source.getBalance().compareTo(amount) < 0) {
            throw new TransferConflictException("Source account has insufficient funds.");
        }
        BigDecimal destinationBalance = destination.getBalance().add(amount);
        if (destinationBalance.compareTo(MAX_BALANCE) > 0) {
            throw new TransferConflictException("Destination account balance would exceed the supported limit.");
        }

        // These are managed entities; dirty checking writes both changes in this transaction.
        source.setBalance(source.getBalance().subtract(amount));
        destination.setBalance(destinationBalance);
        Transaction transaction = new Transaction(source, destination, amount);
        transaction.setStatus(TransactionStatus.COMPLETED);
        Transaction saved = transactions.saveAndFlush(transaction);

        return new TransferResponse(saved.getId(), source.getId(), destination.getId(),
                saved.getAmount(), saved.getStatus(), saved.getCreatedAt());
    }

    private Account lockAccount(Long id) {
        return accounts.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }
}
