package com.quantgen.risk;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.quantgen.risk.LoanDocumentService.Extracted;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-RISK-5: the Underwriting Analyst writes an advisory memo for a pending loan application with documents (D-031:
 * the admin decides). Runs off the request path: claims a couple of queued memos whose last upload is
 * {@link #SETTLE} old, reads the PDFs' text (images are never sent: they can't be masked), masks it, and asks
 * agent-quant. The answer is checked, not trusted: fields and recommendation from fixed sets, every extracted value
 * must be printed on the page it cites, and the income comparison is the core's own arithmetic. Three failed
 * attempts mark the memo FAILED. Nothing here changes the loan.
 */
@Component
class UnderwritingWorker {

    private static final Logger log = LoggerFactory.getLogger(UnderwritingWorker.class);
    static final int MAX_ATTEMPTS = 3;
    /** Uploads within this window share one run. */
    static final Duration SETTLE = Duration.ofSeconds(30);
    static final Duration STALE_CLAIM = Duration.ofMinutes(5);
    static final Set<String> FIELDS = Set.of("MONTHLY_GROSS_INCOME", "MONTHLY_NET_INCOME", "EMPLOYER", "EXISTING_EMI",
            "OTHER_OBLIGATION", "STATEMENT_PERIOD", "AVERAGE_BALANCE");
    static final Set<String> RECOMMENDATIONS = Set.of("APPROVE", "REJECT", "REVIEW");
    static final int MAX_EXTRACTED = 20;
    static final int MAX_MODEL_FLAGS = 6;
    /** Declared income more than this much above the documented net income is flagged. */
    static final BigDecimal INCOME_TOLERANCE = new BigDecimal("1.20");
    static final String FAILED_FLAG = "The Underwriting Analyst couldn't write a memo; review the documents yourself.";

    private record Doc(UUID id, String fileName, String contentType, String storageKey) {
    }

    private record Claim(UUID loanId, Timestamp requestedAt) {
    }

    private final JdbcTemplate jdbc;
    private final LoanDocumentService documents;
    private final AgentClient agent;
    private final CopilotService copilot;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    UnderwritingWorker(JdbcTemplate jdbc, LoanDocumentService documents, AgentClient agent, CopilotService copilot,
                       JsonMapper json, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.agent = agent;
        this.copilot = copilot;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${qg.risk.underwriting-poll-millis:30000}",
            initialDelayString = "${qg.risk.memo-initial-millis:15000}")
    void poll() {
        try {
            runOnce();
        } catch (RuntimeException e) {
            log.error("Underwriting worker failed", e);
        }
    }

    /** Writes memos for up to 2 queued applications; returns how many it wrote. Does nothing while the ai flag is off. */
    int runOnce() {
        if (!copilot.enabled()) {
            return 0;
        }
        Instant now = clock.instant();
        List<Claim> claimed = tx.execute(st -> jdbc.query("""
                UPDATE underwriting_memos SET claimed_at = ?, attempts = attempts + 1
                 WHERE loan_id IN (SELECT m.loan_id FROM underwriting_memos m JOIN loans l ON l.id = m.loan_id
                                    WHERE m.status = 'PENDING' AND l.status = 'PENDING' AND m.attempts < ?
                                      AND m.requested_at <= ? AND (m.claimed_at IS NULL OR m.claimed_at < ?)
                                    ORDER BY m.requested_at LIMIT 2 FOR UPDATE OF m SKIP LOCKED)
                RETURNING loan_id, requested_at
                """, (rs, i) -> new Claim(rs.getObject("loan_id", UUID.class), rs.getTimestamp("requested_at")),
                Timestamp.from(now), MAX_ATTEMPTS, Timestamp.from(now.minus(SETTLE)),
                Timestamp.from(now.minus(STALE_CLAIM))));
        int written = 0;
        for (Claim c : claimed) {
            if (write(c)) {
                written++;
            }
        }
        return written;
    }

    private boolean write(Claim claim) {
        UUID loanId = claim.loanId();
        List<String> flags = new ArrayList<>();
        Map<UUID, Map<Integer, String>> pageText = new HashMap<>();
        Map<String, Object> facts = facts(loanId, flags, pageText);
        JsonNode res = null;
        String error = null;
        if (!pageText.isEmpty()) { // else nothing the model may read: the core's flags are the memo
            try {
                res = agent.backOffice("/internal/v1/memos/underwriting", Map.of("facts", facts));
                error = res.path("error").path("code").asString(null);
            } catch (ApiException e) {
                error = e.code().name();
            }
        }
        JsonNode m = res == null ? null : res.path("memo");
        String memoText = null;
        String recommendation = "REVIEW";
        List<Extracted> extracted = new ArrayList<>();
        if (error == null && m != null) {
            memoText = m.path("memo").asString("").strip();
            if (memoText.isEmpty() || memoText.length() > 2000
                    || !memoText.replace("\n", " ").matches(TextRules.NO_CONTROL_CHARS)
                    || !RECOMMENDATIONS.contains(m.path("recommendation").asString(""))) {
                error = "INVALID_MEMO";
            } else {
                recommendation = m.path("recommendation").asString();
                int dropped = 0;
                for (JsonNode e : m.path("extracted")) {
                    Extracted x = cited(e, pageText);
                    if (x == null) {
                        dropped++;
                    } else if (extracted.size() < MAX_EXTRACTED) {
                        extracted.add(x);
                    }
                }
                if (dropped > 0) {
                    flags.add(dropped + (dropped == 1 ? " value the Analyst cited wasn't" : " values the Analyst cited weren't")
                            + " on the cited page and " + (dropped == 1 ? "was" : "were") + " left out.");
                }
                int n = 0;
                for (JsonNode f : m.path("flags")) {
                    String flag = f.asString("").strip();
                    if (!flag.isEmpty() && flag.length() <= 200 && flag.matches(TextRules.NO_CONTROL_CHARS)
                            && n++ < MAX_MODEL_FLAGS) {
                        flags.add(flag);
                    }
                }
            }
        }
        UUID runId = res == null && error == null ? null : copilot.recordBackOfficeRun("UNDERWRITING", res, error);
        if (error != null) {
            log.warn("Underwriting memo for loan {} failed: {}", loanId, error);
            // FAILED after the last attempt, else back in the queue; only if no new upload re-queued it meanwhile.
            jdbc.update("""
                    UPDATE underwriting_memos SET status = CASE WHEN attempts >= ? THEN 'FAILED' ELSE 'PENDING' END,
                           flags = CASE WHEN attempts >= ? THEN ? ELSE flags END, run_id = ?, claimed_at = NULL
                     WHERE loan_id = ? AND requested_at = ?
                    """, MAX_ATTEMPTS, MAX_ATTEMPTS, json.writeValueAsString(List.of(FAILED_FLAG)), runId, loanId,
                    claim.requestedAt());
            return false;
        }
        incomeCheck(loanId, extracted, flags);
        // The core never lets an APPROVE stand when it couldn't confirm the income or found a mismatch.
        if ("APPROVE".equals(recommendation) && !flags.isEmpty()) {
            recommendation = "REVIEW";
        }
        if (memoText == null) {
            memoText = "No document could be read by the Analyst; see the flags.";
        }
        int updated = jdbc.update("""
                UPDATE underwriting_memos SET status = 'READY', memo = ?, extracted = ?, flags = ?, recommendation = ?,
                       generated_at = ?, run_id = ?, claimed_at = NULL
                 WHERE loan_id = ? AND requested_at = ?
                """, memoText, json.writeValueAsString(extracted), json.writeValueAsString(flags), recommendation,
                Timestamp.from(clock.instant()), runId, loanId, claim.requestedAt());
        return updated == 1;
    }

    /** A cited value the core could find on its page, or null. */
    private Extracted cited(JsonNode e, Map<UUID, Map<Integer, String>> pageText) {
        String field = e.path("field").asString("");
        String value = e.path("value").asString("").strip();
        int page = e.path("page").asInt(0);
        UUID doc;
        try {
            doc = UUID.fromString(e.path("documentId").asString(""));
        } catch (IllegalArgumentException ex) {
            return null;
        }
        String text = pageText.getOrDefault(doc, Map.of()).get(page);
        if (!FIELDS.contains(field) || value.isEmpty() || value.length() > 120
                || !value.matches(TextRules.NO_CONTROL_CHARS) || text == null || !DocumentText.appearsOn(value, text)) {
            return null;
        }
        return new Extracted(field, value, page, doc);
    }

    /** D-031 support, by the core's own arithmetic: declared income vs the documented net income. */
    private void incomeCheck(UUID loanId, List<Extracted> extracted, List<String> flags) {
        BigDecimal declared = jdbc.queryForObject("SELECT monthly_income FROM loans WHERE id = ?", BigDecimal.class, loanId);
        BigDecimal documented = extracted.stream().filter(x -> x.field().equals("MONTHLY_NET_INCOME"))
                .map(x -> DocumentText.amount(x.value())).filter(a -> a != null && a.signum() > 0).findFirst()
                .orElse(null);
        if (documented == null) {
            flags.add("No net monthly income could be confirmed from the documents.");
        } else if (declared != null && declared.compareTo(documented.multiply(INCOME_TOLERANCE)) > 0) {
            flags.add("Declared income ₹" + Money.toJson(declared) + " is more than 20% above the documented net income ₹"
                    + Money.toJson(documented.setScale(2, RoundingMode.HALF_UP)) + ".");
        }
    }

    /**
     * What the Analyst may see: the application's numbers and the masked text of each PDF page. No name, email or full
     * number. Images and unreadable PDFs become core flags. Fills {@code pageText} with what was sent, for citations.
     */
    private Map<String, Object> facts(UUID loanId, List<String> flags, Map<UUID, Map<Integer, String>> pageText) {
        Map<String, Object> app = jdbc.queryForObject("""
                SELECT l.amount, l.tenure_months, l.emi, l.monthly_income, l.purpose,
                       (SELECT COALESCE(sum(o.emi), 0) FROM loans o WHERE o.user_id = l.user_id AND o.id <> l.id
                           AND o.status IN ('PENDING', 'APPROVED', 'ACTIVE')) AS existing_emis
                  FROM loans l WHERE l.id = ?
                """, (rs, i) -> {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("amount", Money.toJson(rs.getBigDecimal("amount")));
            a.put("tenureMonths", rs.getInt("tenure_months"));
            a.put("emi", Money.toJson(rs.getBigDecimal("emi")));
            a.put("declaredMonthlyIncome", Money.toJson(rs.getBigDecimal("monthly_income")));
            a.put("existingEmis", Money.toJson(rs.getBigDecimal("existing_emis")));
            a.put("purpose", DocumentText.mask(rs.getString("purpose")));
            return a;
        }, loanId);
        List<Doc> docs = jdbc.query("SELECT id, file_name, content_type, storage_key FROM loan_documents WHERE loan_id = ? "
                + "ORDER BY uploaded_at, id", (rs, i) -> new Doc(rs.getObject("id", UUID.class), rs.getString("file_name"),
                rs.getString("content_type"), rs.getString("storage_key")), loanId);
        List<Map<String, Object>> sent = new ArrayList<>();
        for (Doc d : docs) {
            if (!"application/pdf".equals(d.contentType())) {
                flags.add(d.fileName() + " is an image; the Analyst doesn't read images, so check it yourself.");
                continue;
            }
            List<DocumentText.Page> pages;
            try {
                pages = DocumentText.pdfPages(documents.read(d.storageKey()));
            } catch (IOException | RuntimeException e) {
                log.warn("Loan document {} couldn't be read: {}", d.id(), e.getClass().getSimpleName());
                flags.add(d.fileName() + " couldn't be read (damaged or password-protected).");
                continue;
            }
            if (pages.isEmpty()) {
                flags.add(d.fileName() + " has no readable text (a scan?); check it yourself.");
                continue;
            }
            Map<Integer, String> byPage = new HashMap<>();
            List<Map<String, Object>> pageList = new ArrayList<>();
            for (DocumentText.Page p : pages) {
                byPage.put(p.page(), p.text());
                pageList.add(Map.of("page", p.page(), "text", p.text()));
            }
            pageText.put(d.id(), byPage);
            sent.add(Map.of("documentId", d.id().toString(), "fileName", d.fileName(), "pages", pageList));
        }
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("application", app);
        f.put("documents", sent);
        return f;
    }
}
