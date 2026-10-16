package com.quantgen.loan.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LoanInstallmentRepository extends JpaRepository<LoanInstallment, UUID> {

    List<LoanInstallment> findByLoanIdOrderByInstallmentNoAsc(UUID loanId);

    List<LoanInstallment> findByLoanIdInOrderByLoanIdAscInstallmentNoAsc(Collection<UUID> loanIds);

    /** Ids of active loans that have an unpaid instalment due on or before {@code date}. */
    @Query("""
            select distinct i.loanId from LoanInstallment i, Loan l
             where l.id = i.loanId and l.status = com.quantgen.loan.domain.LoanStatus.ACTIVE
               and i.status <> com.quantgen.loan.domain.InstallmentStatus.PAID and i.dueDate <= :date
            """)
    List<UUID> findLoanIdsWithDuesOnOrBefore(LocalDate date);

    /** PENDING instalments of active loans due exactly on {@code date} that have not had a reminder yet. */
    @Query("""
            select i from LoanInstallment i, Loan l
             where l.id = i.loanId and l.status = com.quantgen.loan.domain.LoanStatus.ACTIVE
               and i.status = com.quantgen.loan.domain.InstallmentStatus.PENDING and i.dueDate = :date
               and i.reminderSentAt is null
            """)
    List<LoanInstallment> findNeedingReminder(LocalDate date);
}
