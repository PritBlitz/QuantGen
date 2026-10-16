package com.quantgen.loan.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** One row of an EMI schedule. Payments fill interest first, then principal. */
@Entity
@Table(name = "loan_installments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LoanInstallment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "loan_id", nullable = false, updatable = false)
    private UUID loanId;

    @Column(name = "installment_no", nullable = false, updatable = false)
    private int installmentNo;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal emi;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal principal;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal interest;

    @Column(name = "principal_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal principalPaid;

    @Column(name = "interest_paid", nullable = false, precision = 19, scale = 4)
    private BigDecimal interestPaid;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstallmentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Version
    private Long version;

    public LoanInstallment(UUID loanId, int installmentNo, LocalDate dueDate, BigDecimal emi, BigDecimal principal,
                           BigDecimal interest) {
        this.loanId = loanId;
        this.installmentNo = installmentNo;
        this.dueDate = dueDate;
        this.emi = emi;
        this.principal = principal;
        this.interest = interest;
        this.principalPaid = BigDecimal.ZERO;
        this.interestPaid = BigDecimal.ZERO;
        this.status = InstallmentStatus.PENDING;
    }

    public BigDecimal remainingInterest() {
        return interest.subtract(interestPaid);
    }

    public BigDecimal remainingPrincipal() {
        return principal.subtract(principalPaid);
    }

    public BigDecimal remaining() {
        return remainingInterest().add(remainingPrincipal());
    }

    public boolean isPaid() {
        return status == InstallmentStatus.PAID;
    }

    /** Records a payment towards this instalment; it becomes PAID once nothing remains. */
    public void pay(BigDecimal towardsInterest, BigDecimal towardsPrincipal, Instant now) {
        if (towardsInterest.compareTo(remainingInterest()) > 0 || towardsPrincipal.compareTo(remainingPrincipal()) > 0) {
            throw new IllegalArgumentException("Payment exceeds what is due on instalment " + installmentNo);
        }
        interestPaid = interestPaid.add(towardsInterest);
        principalPaid = principalPaid.add(towardsPrincipal);
        if (remaining().signum() == 0) {
            status = InstallmentStatus.PAID;
            paidAt = now;
        }
    }

    /** @return true if this call changed the status (so the customer is notified only once) */
    public boolean markOverdue() {
        if (status != InstallmentStatus.PENDING) {
            return false;
        }
        status = InstallmentStatus.OVERDUE;
        return true;
    }

    public void markReminderSent(Instant now) {
        reminderSentAt = now;
    }
}
