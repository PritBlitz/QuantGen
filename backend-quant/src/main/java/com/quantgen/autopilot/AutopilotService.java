package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiError.FieldError;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.flags.FeatureFlagService;
import com.quantgen.common.web.PageDto;
import com.quantgen.common.web.TextRules;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-AUTO-1/2/4/7/8: Autopilot rules. Activation (create, edit, resume) needs a RULE_ACTIVATE step-up; every edit is
 * a new version; at most 10 active rules per user. The `autopilot` flag is the kill switch: off, nothing can be
 * activated and the engine runs nothing, but rules can still be listed, paused and deleted.
 */
@Service
public class AutopilotService {

    public static final String FLAG = "autopilot";
    static final int MAX_ACTIVE = 10;

    public record RuleDto(UUID id, String name, int version, String status, JsonNode rule, String rendering,
                          String pausedReason, Instant lastRunAt, long runCount, BigDecimal movedThisMonth,
                          Instant createdAt, Instant updatedAt) {
    }

    public record ValidateDto(boolean valid, String rendering, List<FieldError> fieldErrors) {
    }

    public record RunActionDto(String type, BigDecimal amount, UUID transferId, UUID orderId, String error) {
    }

    public record RunDto(UUID id, int ruleVersion, String triggerEventId, String triggerSummary, Instant startedAt,
                         String outcome, List<RunActionDto> actions) {
    }

    public record TemplateDto(String id, String name, String description, String prompt, JsonNode rule) {
    }

    static final List<TemplateDto> TEMPLATES = List.of(
            new TemplateDto("salary-splitter", "Salary splitter",
                    "Split every salary between goals and investments the moment it lands.",
                    "When my salary arrives, move 20% to my goal and buy ₹5,000 of QGEN", null),
            new TemplateDto("sip", "Monthly SIP", "Invest a fixed amount on the same day every month.",
                    "Every month on the 5th buy ₹8,000 of TCS", null),
            new TemplateDto("lean-checking", "Keep checking lean",
                    "Anything above a buffer is swept into savings every day.",
                    "Keep ₹50,000 in checking and sweep the rest to my emergency fund", null),
            new TemplateDto("rent", "Pay rent on the 1st", "Rent goes out on the 1st, automatically, within your caps.",
                    "On the 1st of every month move ₹12,000 to my landlord", null),
            new TemplateDto("buy-the-dip", "Buy the dip", "Buy a stock you like when it falls below a price.",
                    "When QGEN drops below ₹1,200 buy ₹10,000 of it", null),
            new TemplateDto("round-up-saver", "Round-up saver",
                    "Every week, the spare change from your spends goes to a goal.",
                    "Every Sunday move the round-ups to the next ₹10 on my spends from savings to my goal", null),
            new TemplateDto("budget-guard", "Budget guard",
                    "Get a nudge, or top up from savings, when a budget runs hot.",
                    "When my food spending reaches 90% of its budget, notify me", null));

    private final JdbcTemplate jdbc;
    private final RuleValidator validator;
    private final RuleRenderer renderer;
    private final StepUpService stepUp;
    private final FeatureFlagService flags;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    AutopilotService(JdbcTemplate jdbc, RuleValidator validator, RuleRenderer renderer, StepUpService stepUp,
                     FeatureFlagService flags, AuditService audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.renderer = renderer;
        this.stepUp = stepUp;
        this.flags = flags;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public void requireEnabled() {
        if (!flags.isEnabled(FLAG)) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Autopilot is switched off");
        }
    }

    // ------------------------------------------------------------------ validate

    public ValidateDto validate(UUID userId, JsonNode rule) {
        requireEnabled();
        RuleValidator.Result r = validator.validate(userId, rule);
        return new ValidateDto(r.valid(), r.valid() ? renderer.render(r.rule()) : null, r.errors());
    }

    /** The validated rule, or 422 RULE_INVALID with the field errors. */
    public RuleDsl requireValid(UUID userId, JsonNode rule) {
        RuleValidator.Result r = validator.validate(userId, rule);
        if (!r.valid()) {
            throw ApiException.ruleInvalid(r.errors());
        }
        return r.rule();
    }

    public String render(RuleDsl rule) {
        return renderer.render(rule);
    }

    // ------------------------------------------------------------------ rules

    @Transactional(noRollbackFor = ApiException.class)
    public RuleDto create(UUID userId, String name, JsonNode rule, String stepUpToken) {
        requireEnabled();
        RuleDsl dsl = requireValid(userId, rule);
        String clean = name(name);
        stepUp.require(userId, StepUpAction.RULE_ACTIVATE, stepUpToken);
        return insert(userId, clean, dsl, "RULE_ACTIVATED");
    }

    /** From an approved AUTOPILOT_RULE intent: the intent approval was the step-up. */
    @Transactional
    public RuleDto createApproved(UUID userId, String name, RuleDsl dsl) {
        requireEnabled();
        return insert(userId, name(name), dsl, "RULE_ACTIVATED_BY_INTENT");
    }

    private RuleDto insert(UUID userId, String name, RuleDsl dsl, String auditAction) {
        lockUser(userId);
        requireRoomForActive(userId);
        UUID id = jdbc.queryForObject("INSERT INTO autopilot_rules (user_id, name, trigger_type) VALUES (?, ?, ?) RETURNING id",
                UUID.class, userId, name, dsl.trigger().type());
        jdbc.update("INSERT INTO autopilot_rule_versions (rule_id, version, dsl) VALUES (?, 1, ?)", id,
                json.writeValueAsString(dsl));
        audit.record(userId, auditAction, "RULE", id.toString(), renderer.render(dsl));
        return get(userId, id);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public RuleDto update(UUID userId, UUID ruleId, String name, JsonNode rule, String stepUpToken) {
        requireEnabled();
        Row row = lock(userId, ruleId);
        RuleDsl dsl = requireValid(userId, rule);
        String clean = name == null || name.isBlank() ? row.name() : name(name);
        stepUp.require(userId, StepUpAction.RULE_ACTIVATE, stepUpToken);
        int version = row.version() + 1;
        jdbc.update("INSERT INTO autopilot_rule_versions (rule_id, version, dsl) VALUES (?, ?, ?)", ruleId, version,
                json.writeValueAsString(dsl));
        jdbc.update("UPDATE autopilot_rules SET name = ?, version = ?, trigger_type = ?, updated_at = now() WHERE id = ?",
                clean, version, dsl.trigger().type(), ruleId);
        audit.record(userId, "RULE_UPDATED", "RULE", ruleId.toString(), "v" + version + ": " + renderer.render(dsl));
        return get(userId, ruleId);
    }

    @Transactional
    public RuleDto pause(UUID userId, UUID ruleId) {
        Row row = lock(userId, ruleId);
        if (!"ACTIVE".equals(row.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This rule is not active");
        }
        jdbc.update("UPDATE autopilot_rules SET status = 'PAUSED', paused_reason = 'Paused by you.', updated_at = now() "
                + "WHERE id = ?", ruleId);
        audit.record(userId, "RULE_PAUSED", "RULE", ruleId.toString(), null);
        return get(userId, ruleId);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public RuleDto resume(UUID userId, UUID ruleId, String stepUpToken) {
        requireEnabled();
        Row row = lock(userId, ruleId);
        if (!"PAUSED".equals(row.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This rule is not paused");
        }
        lockUser(userId);
        requireRoomForActive(userId);
        stepUp.require(userId, StepUpAction.RULE_ACTIVATE, stepUpToken);
        jdbc.update("UPDATE autopilot_rules SET status = 'ACTIVE', paused_reason = NULL, consecutive_failures = 0, "
                + "updated_at = now() WHERE id = ?", ruleId);
        audit.record(userId, "RULE_RESUMED", "RULE", ruleId.toString(), null);
        return get(userId, ruleId);
    }

    @Transactional
    public void delete(UUID userId, UUID ruleId) {
        lock(userId, ruleId);
        jdbc.update("UPDATE autopilot_rules SET status = 'DELETED', paused_reason = NULL, updated_at = now() WHERE id = ?",
                ruleId);
        audit.record(userId, "RULE_DELETED", "RULE", ruleId.toString(), null);
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<RuleDto> list(UUID userId) {
        return jdbc.query(SELECT + " WHERE r.user_id = ? AND r.status <> 'DELETED' ORDER BY r.created_at DESC, r.id",
                (rs, i) -> dto(rs), monthStart(), userId);
    }

    @Transactional(readOnly = true)
    public RuleDto get(UUID userId, UUID ruleId) {
        return jdbc.query(SELECT + " WHERE r.id = ? AND r.user_id = ? AND r.status <> 'DELETED'", (rs, i) -> dto(rs),
                monthStart(), ruleId, userId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Rule"));
    }

    @Transactional(readOnly = true)
    public PageDto<RunDto> runs(UUID userId, UUID ruleId, Pageable pageable) {
        get(userId, ruleId);
        Long total = jdbc.queryForObject("SELECT count(*) FROM autopilot_runs WHERE rule_id = ?", Long.class, ruleId);
        List<RunDto> content = jdbc.query("""
                SELECT id, rule_version, trigger_event_id, trigger_summary, started_at, outcome FROM autopilot_runs
                 WHERE rule_id = ? ORDER BY started_at DESC, id LIMIT ? OFFSET ?
                """, (rs, i) -> new RunDto(rs.getObject("id", UUID.class), rs.getInt("rule_version"),
                rs.getString("trigger_event_id"), rs.getString("trigger_summary"),
                rs.getTimestamp("started_at").toInstant(), rs.getString("outcome"), new ArrayList<>()),
                ruleId, pageable.getPageSize(), pageable.getOffset());
        for (RunDto run : content) {
            run.actions().addAll(jdbc.query("SELECT type, amount, transfer_id, order_id, error FROM autopilot_run_actions "
                            + "WHERE run_id = ? ORDER BY idx",
                    (rs, i) -> new RunActionDto(rs.getString("type"), rs.getBigDecimal("amount"),
                            rs.getObject("transfer_id", UUID.class), rs.getObject("order_id", UUID.class),
                            rs.getString("error")), run.id()));
        }
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    /** The DSL of one version (the engine and the run history read it). */
    RuleDsl version(UUID ruleId, int version) {
        String dsl = jdbc.queryForObject("SELECT dsl FROM autopilot_rule_versions WHERE rule_id = ? AND version = ?",
                String.class, ruleId, version);
        return json.readValue(dsl, RuleDsl.class);
    }

    // ------------------------------------------------------------------ helpers

    private record Row(String name, String status, int version) {
    }

    private Row lock(UUID userId, UUID ruleId) {
        return jdbc.query("SELECT name, status, version FROM autopilot_rules WHERE id = ? AND user_id = ? "
                        + "AND status <> 'DELETED' FOR UPDATE",
                (rs, i) -> new Row(rs.getString("name"), rs.getString("status"), rs.getInt("version")), ruleId, userId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Rule"));
    }

    private void lockUser(UUID userId) {
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
    }

    private void requireRoomForActive(UUID userId) {
        Integer active = jdbc.queryForObject("SELECT count(*) FROM autopilot_rules WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
        if (active != null && active >= MAX_ACTIVE) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "At most " + MAX_ACTIVE + " active rules; pause one first");
        }
    }

    private static String name(String raw) {
        String n = raw == null ? "" : raw.strip();
        if (n.isEmpty() || n.length() > 40 || !n.matches(TextRules.NO_CONTROL_CHARS)) {
            throw ApiException.ruleInvalid(List.of(new FieldError("name", "must be 1–40 characters")));
        }
        return n;
    }

    private Timestamp monthStart() {
        return Timestamp.from(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).withDayOfMonth(1)
                .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
    }

    private static final String SELECT = """
            SELECT r.id, r.name, r.version, r.status, r.paused_reason, r.created_at, r.updated_at, v.dsl,
                   (SELECT max(started_at) FROM autopilot_runs x WHERE x.rule_id = r.id) AS last_run_at,
                   (SELECT count(*) FROM autopilot_runs x WHERE x.rule_id = r.id) AS run_count,
                   (SELECT COALESCE(sum(a.amount), 0) FROM autopilot_run_actions a JOIN autopilot_runs x ON x.id = a.run_id
                     WHERE x.rule_id = r.id AND a.status = 'DONE'
                       AND a.type IN ('TRANSFER', 'BUY', 'ROUND_UP_SWEEP', 'PAY_BILL')
                       AND a.created_at >= ?) AS moved
              FROM autopilot_rules r JOIN autopilot_rule_versions v ON v.rule_id = r.id AND v.version = r.version
            """;

    private RuleDto dto(ResultSet rs) throws SQLException {
        RuleDsl dsl = json.readValue(rs.getString("dsl"), RuleDsl.class);
        Timestamp last = rs.getTimestamp("last_run_at");
        return new RuleDto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getInt("version"),
                rs.getString("status"), json.valueToTree(dsl), renderer.render(dsl), rs.getString("paused_reason"),
                last == null ? null : last.toInstant(), rs.getLong("run_count"), rs.getBigDecimal("moved"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    static void requireText(String text) {
        if (text == null || text.strip().length() < 8 || text.strip().length() > 500) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "text", "describe the rule in 8–500 characters");
        }
    }
}
