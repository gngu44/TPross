package com.tpross.repository;

import com.tpross.dto.TransferResponse;
import com.tpross.model.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    @Query("""
            select new com.tpross.dto.TransferResponse(
                t.id, t.sourceAccount.id, t.destinationAccount.id, t.amount, t.status, t.createdAt)
            from Transaction t
            where t.sourceAccount.id = :accountId or t.destinationAccount.id = :accountId
            order by t.createdAt desc, t.id desc
            """)
    Slice<TransferResponse> findHistoryByAccountId(@Param("accountId") Long accountId, Pageable pageable);
}
