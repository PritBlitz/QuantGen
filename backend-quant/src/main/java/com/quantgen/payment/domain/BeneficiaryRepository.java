package com.quantgen.payment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, UUID> {

    List<Beneficiary> findByUserIdOrderByNicknameAsc(UUID userId);

    Optional<Beneficiary> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndAccountNumber(UUID userId, String accountNumber);

    long countByUserId(UUID userId);
}
