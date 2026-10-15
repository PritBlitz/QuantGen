package com.quantgen.ledger.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    boolean existsByReversesEntryId(UUID reversesEntryId);
}
