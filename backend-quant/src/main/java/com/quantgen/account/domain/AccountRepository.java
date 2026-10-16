package com.quantgen.account.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserIdOrderByCreatedAtAsc(UUID userId);

    Optional<Account> findByIdAndUserId(UUID id, UUID userId);

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    long countByUserIdAndStatusNot(UUID userId, AccountStatus status);

    /** Rows of (userId, count) for the given users. */
    @Query("select a.userId, count(a) from Account a where a.userId in :userIds group by a.userId")
    List<Object[]> countByUserIds(Collection<UUID> userIds);
}
