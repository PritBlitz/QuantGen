package com.quantgen.loan.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanRepaymentRepository extends JpaRepository<LoanRepayment, UUID> {
}
