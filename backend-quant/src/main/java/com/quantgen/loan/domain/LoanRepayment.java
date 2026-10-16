package com.quantgen.loan.domain;

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
import org.hibernate.annotations.Immutable;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Immutable
@Table(name = "loan_repayments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "principal_paid", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal principalPaid;

    @Column(name = "interest_paid", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal interestPaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private RepaymentSource source;

    @Column(name = "journal_entry_id", nullable = false, updatable = false)
    private UUID journalEntryId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public LoanRepayment(UUID loanId, UUID accountId, BigDecimal principalPaid, BigDecimal interestPaid,
                         RepaymentSource source, UUID journalEntryId) {
        this.loanId = loanId;
        this.accountId = accountId;
        this.principalPaid = principalPaid;
        this.interestPaid = interestPaid;
        this.amount = principalPaid.add(interestPaid);
        this.source = source;
        this.journalEntryId = journalEntryId;
    }
}
