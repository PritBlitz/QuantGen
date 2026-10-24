package com.quantgen.trading.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {

    List<Instrument> findAllByOrderBySymbolAsc();

    Optional<Instrument> findBySymbol(String symbol);

    /** Doesn't load the entity, so a later {@link #findBySymbolForShare} in the same transaction reads fresh. */
    boolean existsBySymbol(String symbol);

    /**
     * {@code FOR SHARE}: an order fills at the price read in its own transaction, and a concurrent
     * simulator tick cannot change that price until the order commits.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select i from Instrument i where i.symbol = :symbol")
    Optional<Instrument> findBySymbolForShare(String symbol);
}
