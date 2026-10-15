package com.quantgen.ledger.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    List<LedgerAccount> findByIdIn(Collection<UUID> ids);

    /**
     * {@code SELECT ... ORDER BY id FOR UPDATE}: Postgres locks rows in the sorted order it returns them,
     * so every transaction acquires ledger locks in ascending id order and cannot deadlock (F-LED-4).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LedgerAccount a where a.id in :ids order by a.id")
    List<LedgerAccount> lockAllInIdOrder(Collection<UUID> ids);
}
