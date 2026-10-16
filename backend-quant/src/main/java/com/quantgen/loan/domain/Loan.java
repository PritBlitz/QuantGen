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
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A loan application and, once approved, the loan itself. State changes only through the methods below. */
@Entity
@Table(name = "loans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Loan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "disburse_account_id", nullable = false, updatable = false)
    private UUID disburseAccountId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "tenure_months", nullable = false, updatable = false)
    private int tenureMonths;

    @Column(name = "annual_rate", nullable = false, updatable = false, precision = 7, scale = 4)
    private BigDecimal annualRate;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal emi;

    @Column(name = "monthly_income", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal monthlyIncome;

    @Column(nullable = false, updatable = false, length = 200)
    private String purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LoanStatus status;

    @Column(name = "outstanding_principal", nullable = false, precision = 19, scale = 4)
    private BigDecimal outstandingPrincipal;

    @Column(name = "decision_reason", length = 500)
    private String decisionReason;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "disbursal_entry_id")
    private UUID disbursalEntryId;

    @Column(name = "disbursed_at")
    private Instant disbursedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public Loan(UUID userId, UUID disburseAccountId, BigDecimal amount, int tenureMonths, BigDecimal annualRate,
                BigDecimal emi, BigDecimal monthlyIncome, String purpose) {
        this.userId = userId;
        this.disburseAccountId = disburseAccountId;
        this.amount = amount;
        this.tenureMonths = tenureMonths;
        this.annualRate = annualRate;
        this.emi = emi;
        this.monthlyIncome = monthlyIncome;
        this.purpose = purpose;
        this.status = LoanStatus.PENDING;
        this.outstandingPrincipal = BigDecimal.ZERO;
    }

    /** Approval disburses at once, so the loan moves from APPROVED to ACTIVE within the same transaction. */
    public void activate(UUID adminId, String reason, UUID disbursalEntryId, Instant now) {
        requireStatus(LoanStatus.PENDING);
        this.decidedBy = adminId;
        this.decidedAt = now;
        this.decisionReason = reason;
        this.disbursalEntryId = disbursalEntryId;
        this.disbursedAt = now;
        this.outstandingPrincipal = amount;
        this.status = LoanStatus.ACTIVE;
    }

    public void reject(UUID adminId, String reason, Instant now) {
        requireStatus(LoanStatus.PENDING);
        this.decidedBy = adminId;
        this.decidedAt = now;
        this.decisionReason = reason;
        this.status = LoanStatus.REJECTED;
    }

    public void applyPrincipal(BigDecimal principalPaid) {
        requireStatus(LoanStatus.ACTIVE);
        BigDecimal next = outstandingPrincipal.subtract(principalPaid);
        if (next.signum() < 0) {
            throw new IllegalStateException("Principal repayment exceeds the outstanding principal");
        }
        this.outstandingPrincipal = next;
    }

    public void close(Instant now) {
        requireStatus(LoanStatus.ACTIVE);
        this.status = LoanStatus.CLOSED;
        this.closedAt = now;
    }

    private void requireStatus(LoanStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Loan " + id + " is " + status + ", expected " + expected);
        }
    }
}
