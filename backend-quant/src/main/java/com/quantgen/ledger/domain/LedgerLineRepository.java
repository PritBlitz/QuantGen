package com.quantgen.ledger.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.quantgen.ledger.service.LedgerLineView;

public interface LedgerLineRepository extends JpaRepository<LedgerLine, UUID> {

    String VIEW = "select new com.quantgen.ledger.service.LedgerLineView(l.id, l.journalEntryId, l.ledgerAccountId, "
            + "l.direction, l.amount, l.balanceAfter, e.type, e.description, l.counterparty, l.createdAt) "
            + "from LedgerLine l join JournalEntry e on e.id = l.journalEntryId ";

    @Query(value = VIEW + "where l.ledgerAccountId = :accountId and l.createdAt >= :from and l.createdAt < :to "
            + "order by l.createdAt desc, l.id desc",
            countQuery = "select count(l) from LedgerLine l where l.ledgerAccountId = :accountId "
                    + "and l.createdAt >= :from and l.createdAt < :to")
    Page<LedgerLineView> history(UUID accountId, Instant from, Instant to, Pageable pageable);

    @Query(value = VIEW + "where l.ledgerAccountId = :accountId and l.createdAt >= :from and l.createdAt < :to "
            + "and e.type = :type order by l.createdAt desc, l.id desc",
            countQuery = "select count(l) from LedgerLine l join JournalEntry e on e.id = l.journalEntryId "
                    + "where l.ledgerAccountId = :accountId and l.createdAt >= :from and l.createdAt < :to "
                    + "and e.type = :type")
    Page<LedgerLineView> historyOfType(UUID accountId, Instant from, Instant to, JournalType type, Pageable pageable);

    @Query(VIEW + "where l.ledgerAccountId = :accountId and l.journalEntryId = :entryId")
    List<LedgerLineView> linesOf(UUID accountId, UUID entryId);

    List<LedgerLine> findByJournalEntryId(UUID journalEntryId);
}
