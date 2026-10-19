package com.quantgen.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Page<Transfer> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

    /** Completed and held (under review) transfers to other people since {@code since} (own-account moves excluded). */
    @Query("select coalesce(sum(t.amount), 0) from Transfer t where t.userId = :userId and t.internal = false "
            + "and t.status in (com.quantgen.payment.domain.TransferStatus.COMPLETED, com.quantgen.payment.domain.TransferStatus.PENDING_REVIEW) and t.createdAt >= :since")
    BigDecimal sumOutgoingSince(UUID userId, Instant since);

    /** Completed and held transfers by {@code userId} to one account number since {@code since} (new-payee cooling). */
    @Query("select coalesce(sum(t.amount), 0) from Transfer t where t.userId = :userId "
            + "and t.toAccountNumber = :toAccountNumber and t.internal = false "
            + "and t.status in (com.quantgen.payment.domain.TransferStatus.COMPLETED, com.quantgen.payment.domain.TransferStatus.PENDING_REVIEW) and t.createdAt >= :since")
    BigDecimal sumToAccountSince(UUID userId, String toAccountNumber, Instant since);

    /** Releases a held transfer (V2-M6): COMPLETED with its entry. Returns 0 if it wasn't pending. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE transfers SET status = 'COMPLETED', journal_entry_id = :journalEntryId "
            + "WHERE id = :id AND status = 'PENDING_REVIEW'", nativeQuery = true)
    int markCompleted(UUID id, UUID journalEntryId);

    /** Rejects a held transfer (V2-M6). Returns 0 if it wasn't pending. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE transfers SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING_REVIEW'",
            nativeQuery = true)
    int markRejected(UUID id);
}
