package com.quantgen.risk;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.Money;
import com.quantgen.common.web.TextRules;
import com.quantgen.copilot.AgentClient;
import com.quantgen.copilot.CopilotService;
import com.quantgen.payment.service.HandleService;
import com.quantgen.risk.FraudCaseService.FraudCaseDetailDto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-RISK-3: the Fraud Investigator writes an advisory memo for each held transfer (D-030: rules decide, the model
 * only explains). Runs off the money path: a scheduled worker claims a few PENDING cases, sends agent-quant the case
 * facts (masked, no names), validates the answer and stores it. Three failed attempts mark the memo FAILED; the case
 * queue works the same with or without one. Nothing here changes a case's status or moves money.
 */
@Component
class FraudMemoWorker {

    private static final Logger log = LoggerFactory.getLogger(FraudMemoWorker.class);
    static final int MAX_ATTEMPTS = 3;
    static final Duration STALE_CLAIM = Duration.ofMinutes(5);
    static final Set<String> ACTIONS = Set.of("RELEASE", "REJECT", "CONTACT_CUSTOMER");
    static final Set<String> CONFIDENCE = Set.of("LOW", "MEDIUM", "HIGH");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final JdbcTemplate jdbc;
    private final FraudCaseService cases;
    private final AgentClient agent;
    private final CopilotService copilot;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    FraudMemoWorker(JdbcTemplate jdbc, FraudCaseService cases, AgentClient agent, CopilotService copilot,
                    JsonMapper json, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.agent = agent;
        this.copilot = copilot;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${qg.risk.memo-poll-millis:20000}", initialDelayString = "${qg.risk.memo-initial-millis:15000}")
    void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Fraud memo worker failed", e);
        }
    }

    /** Writes memos for up to 3 waiting cases; returns how many it wrote. Does nothing while the ai flag is off. */
    int runOnce() {
        if (!copilot.enabled()) {
            return 0;
        }
        Timestamp now = Timestamp.from(clock.instant());
        Timestamp stale = Timestamp.from(clock.instant().minus(STALE_CLAIM));
        List<UUID> claimed = tx.execute(st -> {
            jdbc.update("""
                    UPDATE fraud_cases SET memo_status = 'FAILED'
                     WHERE memo_attempts >= ? AND (memo_status = 'PENDING'
                           OR (memo_status = 'RUNNING' AND memo_claimed_at < ?))
                    """, MAX_ATTEMPTS, stale);
            return jdbc.queryForList("""
                    UPDATE fraud_cases SET memo_status = 'RUNNING', memo_claimed_at = ?, memo_attempts = memo_attempts + 1
                     WHERE id IN (SELECT id FROM fraud_cases
                                   WHERE memo_attempts < ? AND (memo_status = 'PENDING'
                                         OR (memo_status = 'RUNNING' AND memo_claimed_at < ?))
                                   ORDER BY created_at LIMIT 3 FOR UPDATE SKIP LOCKED)
                    RETURNING id
                    """, UUID.class, now, MAX_ATTEMPTS, stale);
        });
        int written = 0;
        for (UUID id : claimed) {
            if (write(id)) {
                written++;
            }
        }
        return written;
    }

    private boolean write(UUID caseId) {
        JsonNode res = null;
        String error = null;
        try {
            res = agent.backOffice("/internal/v1/memos/fraud", Map.of("facts", facts(cases.get(caseId))));
            error = res.path("error").path("code").asString(null);
        } catch (ApiException e) {
            error = e.code().name();
        }
        JsonNode m = res == null ? null : res.path("memo");
        if (error == null && (m == null || !valid(m))) {
            error = "INVALID_MEMO";
        }
        UUID runId = copilot.recordBackOfficeRun("FRAUD_MEMO", res, error);
        if (error != null) {
            log.warn("Fraud memo for case {} failed: {}", caseId, error);
            jdbc.update("UPDATE fraud_cases SET memo_status = CASE WHEN memo_attempts >= ? THEN 'FAILED' ELSE 'PENDING' END "
                    + "WHERE id = ? AND memo_status = 'RUNNING'", MAX_ATTEMPTS, caseId);
            return false;
        }
        Map<String, Object> memo = new LinkedHashMap<>();
        memo.put("summary", m.path("summary").asString().strip());
        memo.put("suggestedAction", m.path("suggestedAction").asString());
        memo.put("confidence", m.path("confidence").asString());
        memo.put("generatedAt", clock.instant().toString());
        memo.put("runId", runId.toString());
        jdbc.update("UPDATE fraud_cases SET memo = ?, memo_status = 'READY' WHERE id = ?", json.writeValueAsString(memo),
                caseId);
        return true;
    }

    /** The memo's shape is checked here; the model's words are shown as advice only, never acted on. */
    static boolean valid(JsonNode m) {
        String summary = m.path("summary").asString("");
        return !summary.isBlank() && summary.length() <= 1200
                && summary.replace("\n", " ").matches(TextRules.NO_CONTROL_CHARS)
                && ACTIONS.contains(m.path("suggestedAction").asString(""))
                && CONFIDENCE.contains(m.path("confidence").asString(""));
    }

    /** What the investigator may see: amounts, signals, pattern and history. No names, emails or full numbers. */
    private Map<String, Object> facts(FraudCaseDetailDto c) {
        UUID userId = c.customer().userId();
        Instant created = c.createdAt();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("score", c.score());
        f.put("threshold", 60);
        f.put("amount", Money.toJson(c.amount()));
        f.put("heldAtIst", created.atZone(IST).toLocalDateTime().toString());
        f.put("from", c.fromAccountNumberMasked());
        f.put("to", c.toAccountNumberMasked());
        f.put("toNameMasked", HandleService.maskName(c.toName()));
        f.put("signals", c.signals());
        f.put("customerBaseline", c.customerBaseline());
        f.put("recentTransfers", jdbc.queryForList("""
                SELECT to_char(created_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI') AS at, amount::text AS amount,
                       status, internal
                  FROM transfers WHERE user_id = ? AND id <> ? ORDER BY created_at DESC LIMIT 10
                """, userId, c.transferId()));
        f.put("paidThisPayeeBefore", Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transfers a JOIN transfers b ON b.to_account_id = a.to_account_id
                                WHERE a.id = ? AND b.user_id = a.user_id AND b.id <> a.id AND b.status = 'COMPLETED')
                """, Boolean.class, c.transferId())));
        f.put("pastCases", jdbc.queryForList("""
                SELECT status, count(*) AS n FROM fraud_cases WHERE user_id = ? AND id <> ? GROUP BY status
                """, userId, c.id()));
        return f;
    }
}
