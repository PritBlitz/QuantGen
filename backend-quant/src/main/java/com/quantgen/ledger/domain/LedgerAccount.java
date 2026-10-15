package com.quantgen.ledger.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A balance-bearing account in the double-entry ledger. {@code balance} is a cache of the sum of the
 * account's lines (credits minus debits for credit-normal accounts, the reverse for debit-normal) and
 * is changed only by {@code LedgerService.post}.
 */
@Entity
@Table(name = "ledger_accounts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerAccount {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private LedgerAccountKind kind;

    @Column(updatable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_side", nullable = false, updatable = false)
    private Direction normalSide;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /** A customer account is a liability of the bank, so it is credit-normal. */
    public static LedgerAccount newCustomerAccount() {
        LedgerAccount account = new LedgerAccount();
        account.id = UUID.randomUUID();
        account.kind = LedgerAccountKind.CUSTOMER;
        account.normalSide = Direction.CREDIT;
        account.balance = BigDecimal.ZERO.setScale(4);
        return account;
    }

    public boolean isCustomer() {
        return kind == LedgerAccountKind.CUSTOMER;
    }

    /** The balance after applying one line of the given direction and amount. */
    public BigDecimal balanceAfter(Direction direction, BigDecimal amount) {
        return direction == normalSide ? balance.add(amount) : balance.subtract(amount);
    }

    /** Called only by {@code LedgerService.post}, under a row lock, with the line already recorded. */
    public void applyPostedBalance(BigDecimal newBalance) {
        this.balance = newBalance;
    }
}
