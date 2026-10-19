package com.quantgen.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "transfers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Transfer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private UUID toAccountId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "from_account_number", nullable = false, updatable = false, length = 12)
    private String fromAccountNumber;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "to_account_number", nullable = false, updatable = false, length = 12)
    private String toAccountNumber;

    @Column(name = "to_name", nullable = false, updatable = false, length = 100)
    private String toName;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(updatable = false, length = 140)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Column(nullable = false, updatable = false)
    private boolean internal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransferSource source;

    @Column(name = "journal_entry_id", updatable = false)
    private UUID journalEntryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Transfer(UUID userId, UUID fromAccountId, UUID toAccountId, String fromAccountNumber,
                    String toAccountNumber, String toName, BigDecimal amount, String note, boolean internal,
                    UUID journalEntryId, TransferSource source) {
        this.userId = userId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.fromAccountNumber = fromAccountNumber;
        this.toAccountNumber = toAccountNumber;
        this.toName = toName;
        this.amount = amount;
        this.note = note;
        this.internal = internal;
        this.journalEntryId = journalEntryId;
        this.source = source;
        this.status = TransferStatus.COMPLETED;
    }

    /** A transfer to another person held for fraud review: no ledger entry until an admin releases it. */
    public static Transfer pendingReview(UUID userId, UUID fromAccountId, UUID toAccountId, String fromAccountNumber,
                                         String toAccountNumber, String toName, BigDecimal amount, String note,
                                         TransferSource source) {
        Transfer t = new Transfer(userId, fromAccountId, toAccountId, fromAccountNumber, toAccountNumber, toName,
                amount, note, false, null, source);
        t.status = TransferStatus.PENDING_REVIEW;
        return t;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }
}
