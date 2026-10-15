package com.quantgen.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "beneficiaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, length = 50)
    private String nickname;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "account_number", nullable = false, updatable = false, length = 12)
    private String accountNumber;

    @Column(name = "holder_name", nullable = false, length = 100)
    private String holderName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Beneficiary(UUID userId, UUID accountId, String nickname, String accountNumber, String holderName) {
        this.userId = userId;
        this.accountId = accountId;
        this.nickname = nickname;
        this.accountNumber = accountNumber;
        this.holderName = holderName;
    }
}
