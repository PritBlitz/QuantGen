package com.quantgen.loan.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LoanRepository extends JpaRepository<Loan, UUID> {

    List<Loan> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<Loan> findByIdAndUserId(UUID id, UUID userId);

    /** Locks the loan row: every state change and repayment of one loan is serialized on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Loan l where l.id = :id")
    Optional<Loan> lockById(UUID id);

    @Query("select coalesce(sum(l.emi), 0) from Loan l where l.userId = :userId and l.status in :statuses")
    BigDecimal sumEmiByUserAndStatusIn(UUID userId, Collection<LoanStatus> statuses);

    Page<Loan> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    Page<Loan> findByStatusOrderByCreatedAtDescIdDesc(LoanStatus status, Pageable pageable);

    long countByStatus(LoanStatus status);

    @Query("select coalesce(sum(l.outstandingPrincipal), 0) from Loan l where l.status = :status")
    BigDecimal sumOutstandingByStatus(LoanStatus status);
}
