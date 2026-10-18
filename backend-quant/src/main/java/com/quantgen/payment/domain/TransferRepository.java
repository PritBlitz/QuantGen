package com.quantgen.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Page<Transfer> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    /** Completed transfers to other people since {@code since} (own-account moves excluded). */
    @Query("select coalesce(sum(t.amount), 0) from Transfer t where t.userId = :userId and t.internal = false "
            + "and t.status = com.quantgen.payment.domain.TransferStatus.COMPLETED and t.createdAt >= :since")
    BigDecimal sumOutgoingSince(UUID userId, Instant since);

    /** Completed transfers by {@code userId} to one account number since {@code since} (new-payee cooling). */
    @Query("select coalesce(sum(t.amount), 0) from Transfer t where t.userId = :userId "
            + "and t.toAccountNumber = :toAccountNumber and t.internal = false "
            + "and t.status = com.quantgen.payment.domain.TransferStatus.COMPLETED and t.createdAt >= :since")
    BigDecimal sumToAccountSince(UUID userId, String toAccountNumber, Instant since);
}
