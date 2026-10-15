package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.Money;
import com.quantgen.ledger.domain.Direction;
import com.quantgen.ledger.domain.JournalEntry;
import com.quantgen.ledger.domain.JournalEntryRepository;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.domain.LedgerAccount;
import com.quantgen.ledger.domain.LedgerAccountRepository;
import com.quantgen.ledger.domain.LedgerLine;
import com.quantgen.ledger.domain.LedgerLineRepository;

@Service
class LedgerServiceImpl implements LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerServiceImpl.class);

    private final LedgerAccountRepository accounts;
    private final JournalEntryRepository entries;
    private final LedgerLineRepository lines;
    private final PostingGuard guard;
    private final JdbcTemplate jdbc;

    LedgerServiceImpl(LedgerAccountRepository accounts, JournalEntryRepository entries, LedgerLineRepository lines,
                      PostingGuard guard, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.entries = entries;
        this.lines = lines;
        this.guard = guard;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ posting

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry post(JournalRequest request) {
        return doPost(request, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PostedEntry reverse(UUID journalEntryId, String reason, UUID createdBy) {
        JournalEntry original = entries.findById(journalEntryId)
                .orElseThrow(() -> ApiException.notFound("Journal entry"));
        if (original.getType() == JournalType.REVERSAL || entries.existsByReversesEntryId(journalEntryId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This entry cannot be reversed");
        }
        JournalRequest.Builder mirror = JournalRequest.builder(JournalType.REVERSAL,
                "Reversal: " + reason, createdBy);
        for (LedgerLine line : lines.findByJournalEntryId(journalEntryId)) {
            if (line.getDirection() == Direction.DEBIT) {
                mirror.credit(line.getLedgerAccountId(), line.getAmount(), line.getCounterparty());
            } else {
                mirror.debit(line.getLedgerAccountId(), line.getAmount(), line.getCounterparty());
            }
        }
        return doPost(mirror.build(), journalEntryId);
    }

    private PostedEntry doPost(JournalRequest request, UUID reversesEntryId) {
        List<JournalRequest.Line> normalized = validate(request);

        // 1. Lock every involved ledger row, in ascending id order (deadlock-free).
        Set<UUID> ids = normalized.stream().map(JournalRequest.Line::ledgerAccountId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, LedgerAccount> locked = accounts.lockAllInIdOrder(ids).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        if (locked.size() != ids.size()) {
            throw new IllegalArgumentException("Journal references an unknown ledger account");
        }

        // 2. Account-level rules (frozen / closed), checked under the lock.
        Set<UUID> debited = new HashSet<>();
        Set<UUID> credited = new HashSet<>();
        for (JournalRequest.Line line : normalized) {
            if (locked.get(line.ledgerAccountId()).isCustomer()) {
                (line.direction() == Direction.DEBIT ? debited : credited).add(line.ledgerAccountId());
            }
        }
        guard.checkPostable(debited, credited);

        // 3. Compute running balances; a customer balance may never go negative (F-LED-5).
        Map<UUID, BigDecimal> running = new HashMap<>();
        locked.forEach((id, account) -> running.put(id, account.getBalance()));
        List<BigDecimal> balanceAfter = new ArrayList<>(normalized.size());
        for (JournalRequest.Line line : normalized) {
            LedgerAccount account = locked.get(line.ledgerAccountId());
            BigDecimal next = line.direction() == account.getNormalSide()
                    ? running.get(account.getId()).add(line.amount())
                    : running.get(account.getId()).subtract(line.amount());
            if (account.isCustomer() && next.signum() < 0) {
                throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS);
            }
            running.put(account.getId(), next);
            balanceAfter.add(next);
        }

        // 4. Insert the entry and its lines, then update the cached balances.
        JournalEntry entry = entries.save(new JournalEntry(request.type(), truncate(request.description(), 255),
                reversesEntryId, request.createdBy()));
        List<LedgerLine> saved = new ArrayList<>(normalized.size());
        for (int i = 0; i < normalized.size(); i++) {
            JournalRequest.Line line = normalized.get(i);
            saved.add(lines.save(new LedgerLine(entry.getId(), line.ledgerAccountId(), line.direction(),
                    line.amount(), balanceAfter.get(i), truncate(line.counterparty(), 120))));
        }
        running.forEach((id, balance) -> locked.get(id).applyPostedBalance(balance));
        accounts.flush(); // flushes the whole persistence context: entry, lines, balances

        List<LedgerLineView> views = saved.stream()
                .map(l -> new LedgerLineView(l.getId(), entry.getId(), l.getLedgerAccountId(), l.getDirection(),
                        l.getAmount(), l.getBalanceAfter(), entry.getType(), entry.getDescription(),
                        l.getCounterparty(), l.getCreatedAt()))
                .toList();

        log.debug("Posted {} entry {} with {} lines", request.type(), entry.getId(), views.size());
        return new PostedEntry(entry.getId(), entry.getCreatedAt(), List.copyOf(views));
    }

    /** Invariant 1 and line sanity: ≥2 lines, ≥2 accounts, amounts > 0 with scale ≤ 4, Σ debit = Σ credit. */
    private static List<JournalRequest.Line> validate(JournalRequest request) {
        if (request.lines().size() < 2) {
            throw new IllegalArgumentException("A journal entry needs at least two lines");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        List<JournalRequest.Line> normalized = new ArrayList<>(request.lines().size());
        for (JournalRequest.Line line : request.lines()) {
            if (line.amount().signum() <= 0) {
                throw new IllegalArgumentException("Ledger line amounts must be positive");
            }
            BigDecimal amount;
            try {
                amount = Money.normalize(line.amount());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Ledger line amounts may have at most 4 decimal places", e);
            }
            if (line.direction() == Direction.DEBIT) {
                debits = debits.add(amount);
            } else {
                credits = credits.add(amount);
            }
            normalized.add(new JournalRequest.Line(line.ledgerAccountId(), line.direction(), amount,
                    line.counterparty()));
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException("Unbalanced journal entry: debits " + debits + " != credits " + credits);
        }
        if (normalized.stream().map(JournalRequest.Line::ledgerAccountId).distinct().count() < 2) {
            throw new IllegalArgumentException("A journal entry must involve at least two accounts");
        }
        return normalized;
    }

    // ------------------------------------------------------------------ accounts and queries

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openCustomerAccount() {
        return accounts.save(LedgerAccount.newCustomerAccount()).getId();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAccount(UUID ledgerAccountId) {
        if (accounts.lockAllInIdOrder(List.of(ledgerAccountId)).isEmpty()) {
            throw new IllegalArgumentException("Unknown ledger account " + ledgerAccountId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> balancesOf(Collection<UUID> ledgerAccountIds) {
        if (ledgerAccountIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findByIdIn(ledgerAccountIds).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, LedgerAccount::getBalance));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<LedgerLineView> history(UUID ledgerAccountId, Instant from, Instant to, JournalType type,
                                        Pageable pageable) {
        return type == null
                ? lines.history(ledgerAccountId, from, to, pageable)
                : lines.historyOfType(ledgerAccountId, from, to, type, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LedgerLineView> linesOf(UUID ledgerAccountId, UUID journalEntryId) {
        return lines.linesOf(ledgerAccountId, journalEntryId);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconcileReport reconcile() {
        BigDecimal ledgerSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END), 0) FROM ledger_lines",
                BigDecimal.class);
        List<ReconcileReport.Mismatch> mismatches = jdbc.query("""
                SELECT la.id, la.code, la.balance,
                       COALESCE(SUM(CASE WHEN l.direction = la.normal_side THEN l.amount ELSE -l.amount END), 0) AS computed
                  FROM ledger_accounts la
                  LEFT JOIN ledger_lines l ON l.ledger_account_id = la.id
                 GROUP BY la.id, la.code, la.balance
                HAVING la.balance <> COALESCE(SUM(CASE WHEN l.direction = la.normal_side THEN l.amount ELSE -l.amount END), 0)
                 ORDER BY la.id
                """, (rs, i) -> new ReconcileReport.Mismatch(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getBigDecimal("balance"), rs.getBigDecimal("computed")));
        BigDecimal sum = ledgerSum == null ? BigDecimal.ZERO : ledgerSum.setScale(Money.STORAGE_SCALE, RoundingMode.UNNECESSARY);
        boolean balanced = sum.signum() == 0 && mismatches.isEmpty();
        if (!balanced) {
            log.error("Ledger reconciliation FAILED: ledgerSum={} mismatches={}", sum, mismatches.size());
        }
        return new ReconcileReport(balanced, sum, mismatches);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
