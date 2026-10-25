package com.quantgen.risk;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.Money;
import com.quantgen.common.web.TextRules;
import com.quantgen.copilot.AgentClient;
import com.quantgen.copilot.CopilotService;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.ReconcileReport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-LED-8 reconciliation plus the F-RISK-6 explainer. The report is the ledger's own (no model involved); each
 * mismatch it finds is queued, and with the {@code ai} flag on a worker asks agent-quant to explain it from the
 * account's recent entries. The explanation is advice for the admin: it never changes the ledger, and the entries it
 * names must be among those it was shown. Role ADMIN (SecurityConfig, /api/v1/admin/**).
 */
@RestController
@Tag(name = "Admin")
class ReconcileExplainer {

    private static final Logger log = LoggerFactory.getLogger(ReconcileExplainer.class);
    static final int MAX_ATTEMPTS = 3;
    static final int ENTRIES_SHOWN = 20;
    static final Duration STALE_CLAIM = Duration.ofMinutes(5);

    public record ExplanationDto(String summary, String likelyCause, List<UUID> affectedEntries, UUID runId) {
    }

    public record MismatchDto(UUID ledgerAccountId, String code, BigDecimal cachedBalance, BigDecimal computedBalance,
                              ExplanationDto explanation) {
    }

    public record ReconcileDto(boolean balanced, BigDecimal ledgerSum, List<MismatchDto> mismatches) {
    }

    private record Claim(UUID id, UUID accountId, BigDecimal cached, BigDecimal computed) {
    }

    private final LedgerService ledger;
    private final JdbcTemplate jdbc;
    private final AgentClient agent;
    private final CopilotService copilot;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    ReconcileExplainer(LedgerService ledger, JdbcTemplate jdbc, AgentClient agent, CopilotService copilot,
                       JsonMapper json, Clock clock, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.agent = agent;
        this.copilot = copilot;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @GetMapping("/api/v1/admin/ledger/reconcile")
    @Operation(summary = "Check every cached balance against its lines and that the ledger sums to zero; each "
            + "mismatch carries the explainer's advisory note once written (ai flag)")
    public ReconcileDto reconcile() {
        ReconcileReport report = ledger.reconcile();
        List<MismatchDto> out = new ArrayList<>();
        for (ReconcileReport.Mismatch m : report.mismatches()) {
            jdbc.update("""
                    INSERT INTO reconcile_explanations (ledger_account_id, cached_balance, computed_balance)
                    VALUES (?, ?, ?) ON CONFLICT ON CONSTRAINT reconcile_explanations_state_uq DO NOTHING
                    """, m.ledgerAccountId(), m.cachedBalance(), m.computedBalance());
            out.add(new MismatchDto(m.ledgerAccountId(), m.code(), m.cachedBalance(), m.computedBalance(),
                    explanation(m)));
        }
        return new ReconcileDto(report.balanced(), report.ledgerSum(), out);
    }

    private ExplanationDto explanation(ReconcileReport.Mismatch m) {
        return jdbc.query("""
                SELECT summary, likely_cause, affected_entries, run_id FROM reconcile_explanations
                 WHERE ledger_account_id = ? AND cached_balance = ? AND computed_balance = ? AND status = 'READY'
                """, (rs, i) -> new ExplanationDto(rs.getString("summary"), rs.getString("likely_cause"),
                json.readValue(rs.getString("affected_entries"), new TypeReference<List<UUID>>() { }),
                rs.getObject("run_id", UUID.class)), m.ledgerAccountId(), m.cachedBalance(), m.computedBalance())
                .stream().findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ worker

    @Scheduled(fixedDelayString = "${qg.risk.reconcile-poll-millis:60000}",
            initialDelayString = "${qg.risk.memo-initial-millis:15000}")
    void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Reconcile explainer failed", e);
        }
    }

    /** Explains up to 2 queued mismatches; returns how many it wrote. Does nothing while the ai flag is off. */
    int runOnce() {
        if (!copilot.enabled()) {
            return 0;
        }
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp stale = Timestamp.from(clock.instant().minus(STALE_CLAIM));
        List<Claim> claimed = tx.execute(st -> jdbc.query("""
                UPDATE reconcile_explanations SET claimed_at = ?, attempts = attempts + 1
                 WHERE id IN (SELECT id FROM reconcile_explanations
                               WHERE status = 'PENDING' AND attempts < ? AND (claimed_at IS NULL OR claimed_at < ?)
                               ORDER BY created_at LIMIT 2 FOR UPDATE SKIP LOCKED)
                RETURNING id, ledger_account_id, cached_balance, computed_balance
                """, (rs, i) -> new Claim(rs.getObject("id", UUID.class), rs.getObject("ledger_account_id", UUID.class),
                rs.getBigDecimal("cached_balance"), rs.getBigDecimal("computed_balance")), now, MAX_ATTEMPTS, stale));
        int written = 0;
        for (Claim c : claimed) {
            if (write(c)) {
                written++;
            }
        }
        return written;
    }

    private boolean write(Claim c) {
        Set<UUID> shown = new HashSet<>();
        Map<String, Object> facts = facts(c, shown);
        JsonNode res = null;
        String error = null;
        try {
            res = agent.backOffice("/internal/v1/memos/reconcile", Map.of("facts", facts));
            error = res.path("error").path("code").asString(null);
        } catch (ApiException e) {
            error = e.code().name();
        }
        JsonNode m = res == null ? null : res.path("memo");
        String summary = m == null ? "" : m.path("summary").asString("").strip();
        String cause = m == null ? "" : m.path("likelyCause").asString("").strip();
        List<UUID> affected = new ArrayList<>();
        if (error == null) {
            for (JsonNode e : m.path("affectedEntries")) {
                try {
                    UUID id = UUID.fromString(e.asString(""));
                    if (shown.contains(id) && !affected.contains(id)) {
                        affected.add(id); // only entries it was shown: the model can't point anywhere else
                    }
                } catch (IllegalArgumentException ignored) {
                    // not an id: dropped
                }
            }
            if (summary.isEmpty() || summary.length() > 1200 || !summary.replace("\n", " ").matches(TextRules.NO_CONTROL_CHARS)
                    || cause.isEmpty() || cause.length() > 200 || !cause.matches(TextRules.NO_CONTROL_CHARS)) {
                error = "INVALID_MEMO";
            }
        }
        UUID runId = copilot.recordBackOfficeRun("RECONCILE", res, error);
        if (error != null) {
            log.warn("Reconcile explanation {} failed: {}", c.id(), error);
            jdbc.update("UPDATE reconcile_explanations SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END, "
                    + "run_id = ?, claimed_at = NULL WHERE id = ?", MAX_ATTEMPTS, runId, c.id());
            return false;
        }
        jdbc.update("""
                UPDATE reconcile_explanations SET status = 'READY', summary = ?, likely_cause = ?, affected_entries = ?,
                       run_id = ?, claimed_at = NULL WHERE id = ?
                """, summary, cause, json.writeValueAsString(affected), runId, c.id());
        return true;
    }

    /** The account's kind and the difference, plus its latest entries: amounts, types, times; numbers masked. */
    private Map<String, Object> facts(Claim c, Set<UUID> shown) {
        Map<String, Object> f = new LinkedHashMap<>();
        jdbc.query("SELECT kind, code, normal_side FROM ledger_accounts WHERE id = ?", rs -> {
            f.put("accountKind", rs.getString("kind"));
            f.put("systemCode", rs.getString("code"));
            f.put("normalSide", rs.getString("normal_side"));
        }, c.accountId());
        f.put("cachedBalance", Money.toJson(c.cached()));
        f.put("computedFromLines", Money.toJson(c.computed()));
        f.put("difference", Money.toJson(c.cached().subtract(c.computed())));
        List<Map<String, Object>> entries = new ArrayList<>();
        jdbc.query("""
                SELECT e.id, e.type, e.description, e.created_at, l.direction, l.amount,
                       (SELECT count(*) FROM ledger_lines x WHERE x.journal_entry_id = e.id) AS lines
                  FROM ledger_lines l JOIN journal_entries e ON e.id = l.journal_entry_id
                 WHERE l.ledger_account_id = ? ORDER BY e.created_at DESC LIMIT ?
                """, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            shown.add(id);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("journalEntryId", id.toString());
            e.put("type", rs.getString("type"));
            e.put("at", rs.getTimestamp("created_at").toInstant().toString());
            e.put("direction", rs.getString("direction"));
            e.put("amount", Money.toJson(rs.getBigDecimal("amount")));
            e.put("linesInEntry", rs.getInt("lines"));
            e.put("description", DocumentText.mask(rs.getString("description") == null ? "" : rs.getString("description")));
            entries.add(e);
        }, c.accountId(), ENTRIES_SHOWN);
        f.put("latestEntries", entries);
        return f;
    }
}
