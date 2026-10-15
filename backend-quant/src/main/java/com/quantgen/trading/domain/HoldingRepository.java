package com.quantgen.trading.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface HoldingRepository extends JpaRepository<Holding, UUID> {

    List<Holding> findByUserIdOrderBySymbolAsc(UUID userId);

    /** Creates an empty position if none exists (race-safe). */
    @Modifying
    @Query(value = "INSERT INTO holdings (user_id, symbol) VALUES (:userId, :symbol) ON CONFLICT (user_id, symbol) DO NOTHING",
            nativeQuery = true)
    int ensureExists(UUID userId, String symbol);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from Holding h where h.userId = :userId and h.symbol = :symbol")
    Optional<Holding> lockByUserIdAndSymbol(UUID userId, String symbol);
}
