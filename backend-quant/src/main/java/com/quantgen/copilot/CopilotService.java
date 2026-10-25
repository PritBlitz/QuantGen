package com.quantgen.copilot;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.quantgen.auth.service.JwtService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.flags.FeatureFlagService;
import com.quantgen.common.money.Money;
import com.quantgen.common.security.UserRateLimiter;
import com.quantgen.common.web.TextRules;
import com.quantgen.copilot.AgentClient.AgentEvent;
import com.quantgen.copilot.AgentClient.HistoryItem;
import com.quantgen.intent.IntentService;
import com.quantgen.intent.IntentService.IntentDto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-AGT-1/5/6/7 copilot (PRD 12.6). The core owns conversations, messages and traces (D-045); a turn mints a
 * delegated token for the conversation (D-022), checks the {@code ai} flag, the hourly turn limit and the token
 * budgets (D-039), then relays agent-quant's stream to the browser. Intents are re-read from the core, so the card
 * the user sees is always the server's rendering (D-023). Both messages are stored before the stream closes.
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);
    public static final String FLAG = "ai";
    static final UserRateLimiter.Limit TURN_LIMIT = new UserRateLimiter.Limit("copilot-turn", 30, Duration.ofHours(1));
    static final Duration RETENTION = Duration.ofDays(30);
    static final int HISTORY = 12;
    static final int MAX_TEXT = 8000;
    /** Chat text may have line breaks, but no other control characters. */
    static final String CHAT_TEXT = "^(?:[^\\p{Cntrl}]|\\n)*$";
    static final Set<String> CITATION_TYPES = Set.of("TRANSACTION", "ACCOUNT", "INSTRUMENT", "LOAN", "RULE", "METRIC");
    static final Set<String> AGENTS = Set.of("CONCIERGE", "LEDGER_ANALYST", "PAYMENTS", "MARKETS", "PLANNER",
            "LOAN_ADVISOR", "FRAUD_INVESTIGATOR", "UNDERWRITING_ANALYST", "RECONCILE_EXPLAINER");

    public record ConversationDto(UUID id, String title, Instant createdAt, Instant updatedAt) {
    }

    public record Citation(String type, String id, String label) {
    }

    public record ChatMessageDto(UUID id, String role, String text, List<IntentDto> intents, List<Citation> citations,
                                 UUID runId, Instant createdAt) {
    }

    public record ConversationDetailDto(UUID id, String title, Instant createdAt, Instant updatedAt,
                                        List<ChatMessageDto> messages) {
    }

    public record StepDto(String agent, String kind, String name, String status, int latencyMs, int inputTokens,
                          int outputTokens, String detail) {
    }

    public record TraceDto(UUID runId, Instant startedAt, long totalMs, BigDecimal costUsd, List<StepDto> steps) {
    }

    private final JdbcTemplate jdbc;
    private final AgentClient agent;
    private final JwtService jwt;
    private final IntentService intents;
    private final FeatureFlagService flags;
    private final UserRateLimiter rateLimiter;
    private final JsonMapper json;
    private final Clock clock;
    private final AiBudget budget;
    private final ExecutorService relay = Executors.newVirtualThreadPerTaskExecutor();

    CopilotService(JdbcTemplate jdbc, AgentClient agent, JwtService jwt, IntentService intents, FeatureFlagService flags,
                   UserRateLimiter rateLimiter, JsonMapper json, Clock clock, AiBudget budget) {
        this.jdbc = jdbc;
        this.agent = agent;
        this.jwt = jwt;
        this.intents = intents;
        this.flags = flags;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.clock = clock;
        this.budget = budget;
    }

    // ------------------------------------------------------------------ conversations

    public ConversationDto create(UUID userId) {
        requireEnabled();
        UUID id = jdbc.queryForObject("INSERT INTO copilot_conversations (user_id) VALUES (?) RETURNING id", UUID.class,
                userId);
        return conversation(userId, id);
    }

    public List<ConversationDto> list(UUID userId) {
        return jdbc.query("SELECT id, title, created_at, updated_at FROM copilot_conversations WHERE user_id = ? "
                        + "ORDER BY updated_at DESC, id DESC LIMIT 100",
                (rs, i) -> new ConversationDto(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()), userId);
    }

    public ConversationDetailDto get(UUID userId, UUID id) {
        ConversationDto c = conversation(userId, id);
        List<ChatMessageDto> messages = jdbc.query("""
                SELECT id, role, text, intent_ids, citations, run_id, created_at FROM copilot_messages
                 WHERE conversation_id = ? ORDER BY created_at, id
                """, (rs, i) -> {
            List<UUID> ids = rs.getString("intent_ids").isEmpty() ? List.of()
                    : Arrays.stream(rs.getString("intent_ids").split(",")).map(UUID::fromString).toList();
            return new ChatMessageDto(rs.getObject("id", UUID.class), rs.getString("role"), rs.getString("text"),
                    intents.byIds(userId, ids), List.of(json.readValue(rs.getString("citations"), Citation[].class)),
                    rs.getObject("run_id", UUID.class), rs.getTimestamp("created_at").toInstant());
        }, id);
        return new ConversationDetailDto(c.id(), c.title(), c.createdAt(), c.updatedAt(), messages);
    }

    /** Deletes a conversation with its messages and traces at once (F-AGT-7). */
    public void delete(UUID userId, UUID id) {
        if (jdbc.update("DELETE FROM copilot_conversations WHERE id = ? AND user_id = ?", id, userId) == 0) {
            throw ApiException.notFound("Conversation");
        }
    }

    // ------------------------------------------------------------------ a turn

    /**
     * Everything that can fail with a normal JSON error happens before the emitter is returned: the flag, ownership,
     * text, limits, budgets and connecting to the agent. From then on failures are {@code error} events.
     */
    public SseEmitter turn(UUID userId, UUID conversationId, String rawText) {
        return turn(userId, conversationId, rawText, null);
    }

    /** Optional context the user opened the copilot from, e.g. "Explain this run" (F-AUTO-7). */
    public record Context(String type, UUID id) {
    }

    /** {@link #turn(UUID, UUID, String)} with the server-rendered {@code context} added to what the agent sees. */
    public SseEmitter turn(UUID userId, UUID conversationId, String rawText, Context context) {
        requireEnabled();
        ConversationDto c = conversation(userId, conversationId);
        String text = rawText == null ? "" : rawText.replace("\r\n", "\n").strip();
        if (text.isEmpty() || text.length() > 2000 || !text.matches(CHAT_TEXT)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "text", "must be 1–2000 characters");
        }
        String forAgent = context == null ? text : text + "\n\n(Context from the bank, rendered by the server: "
                + context(userId, context) + ")";
        rateLimiter.acquire(TURN_LIMIT, userId);
        budget.requireAvailable(userId);
        List<HistoryItem> history = jdbc.query("""
                SELECT role, text FROM (SELECT role, text, created_at, id FROM copilot_messages
                 WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT ?) h ORDER BY created_at, id
                """, (rs, i) -> new HistoryItem(rs.getString(1).toLowerCase(), rs.getString(2)), conversationId, HISTORY);
        UUID runId = jdbc.queryForObject("INSERT INTO agent_runs (user_id, conversation_id, kind) VALUES (?, ?, 'CHAT') "
                + "RETURNING id", UUID.class, userId, conversationId);
        AgentClient.Turn turn;
        try {
            turn = agent.startTurn(runId, conversationId, jwt.issueAgentToken(userId, conversationId), history, forAgent);
        } catch (ApiException e) {
            finishRun(runId, "ERROR", e.code().name(), 0, 0);
            throw e;
        }
        // Stored once the agent has accepted the turn, so a failed connect leaves no unanswered message.
        jdbc.update("INSERT INTO copilot_messages (conversation_id, role, text, created_at) VALUES (?, 'USER', ?, ?)",
                conversationId, text, Timestamp.from(clock.instant()));
        if ("New conversation".equals(c.title())) {
            jdbc.update("UPDATE copilot_conversations SET title = ? WHERE id = ?", title(text), conversationId);
        }
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(3).toMillis());
        relay.submit(() -> relay(userId, conversationId, runId, turn, emitter));
        return emitter;
    }

    private void relay(UUID userId, UUID conversationId, UUID runId, AgentClient.Turn turn, SseEmitter emitter) {
        StringBuilder answer = new StringBuilder();
        List<UUID> intentIds = new ArrayList<>();
        List<Citation> citations = new ArrayList<>();
        long[] usage = {0, 0};
        String[] error = {null};
        int[] seq = {0};
        try (turn) {
            turn.pump(e -> {
                switch (e.type()) {
                    case "token" -> {
                        String t = e.data().path("text").asString("");
                        if (answer.length() + t.length() <= MAX_TEXT) {
                            answer.append(t);
                            send(emitter, "token", Map.of("text", t));
                        }
                    }
                    case "tool" -> {
                        String agentName = e.data().path("agent").asString("");
                        String status = e.data().path("status").asString("");
                        if (AGENTS.contains(agentName) && Set.of("STARTED", "DONE", "FAILED").contains(status)) {
                            send(emitter, "tool", Map.of("agent", agentName, "tool",
                                    clip(e.data().path("tool").asString(""), 60), "status", status));
                        }
                    }
                    case "intent" -> {
                        // Re-read from the core: only the user's own intent, rendered by the server (D-023).
                        UUID id = uuid(e.data().path("id").asString(""));
                        List<IntentDto> found = id == null ? List.of() : intents.byIds(userId, List.of(id));
                        if (!found.isEmpty() && conversationId.equals(found.getFirst().conversationId())
                                && intentIds.size() < 10) {
                            intentIds.add(id);
                            send(emitter, "intent", found.getFirst());
                        }
                    }
                    case "citation" -> {
                        Citation ci = citation(e.data());
                        if (ci != null && citations.size() < 20) {
                            citations.add(ci);
                            send(emitter, "citation", ci);
                        }
                    }
                    case "step" -> step(runId, ++seq[0], e.data());
                    case "done" -> {
                        usage[0] = e.data().path("usage").path("inputTokens").asLong(0);
                        usage[1] = e.data().path("usage").path("outputTokens").asLong(0);
                    }
                    case "error" -> error[0] = clip(e.data().path("code").asString("INTERNAL"), 30);
                    default -> {
                        // unknown event types are ignored
                    }
                }
            });
        } catch (RuntimeException ex) {
            log.warn("Copilot relay for run {} broke: {}", runId, ex.getClass().getSimpleName());
            error[0] = error[0] == null ? "AI_UNAVAILABLE" : error[0];
        }
        if (error[0] == null && answer.isEmpty() && intentIds.isEmpty()) {
            error[0] = "INTERNAL"; // the agent ended without an answer
        }
        // Persist before closing the stream, so a refetch right after "done" sees both messages.
        try {
            jdbc.update("""
                    INSERT INTO copilot_messages (conversation_id, role, text, intent_ids, citations, run_id, created_at)
                    VALUES (?, 'ASSISTANT', ?, ?, ?, ?, ?)
                    """, conversationId, answer.isEmpty() ? "" : answer.toString(), joinIds(intentIds),
                    json.writeValueAsString(citations), runId, Timestamp.from(clock.instant()));
            jdbc.update("UPDATE copilot_conversations SET updated_at = now() WHERE id = ?", conversationId);
            finishRun(runId, error[0] == null ? "OK" : "ERROR", error[0], usage[0], usage[1]);
        } catch (RuntimeException ex) {
            log.error("Copilot run {} could not be stored", runId, ex);
        }
        if (error[0] != null) {
            send(emitter, "error", Map.of("code", error[0], "message", errorMessage(error[0])));
        } else {
            send(emitter, "done", Map.of("runId", runId, "usage", Map.of("inputTokens", usage[0],
                    "outputTokens", usage[1])));
        }
        emitter.complete();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(json.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            // The browser went away; the turn still finishes and is stored.
        }
    }

    private void step(UUID runId, int seq, JsonNode s) {
        String agentName = s.path("agent").asString("");
        String kind = s.path("kind").asString("");
        if (!AGENTS.contains(agentName) || !Set.of("MODEL", "TOOL").contains(kind)) {
            return;
        }
        jdbc.update("""
                INSERT INTO agent_steps (run_id, seq, agent, kind, name, status, latency_ms, input_tokens, output_tokens,
                                         detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, runId, seq, agentName, kind, clip(s.path("name").asString(""), 60),
                "ERROR".equals(s.path("status").asString("")) ? "ERROR" : "OK", s.path("latencyMs").asInt(0),
                s.path("inputTokens").asInt(0), s.path("outputTokens").asInt(0),
                s.hasNonNull("detail") ? clip(s.path("detail").asString(""), 500) : null);
    }

    void finishRun(UUID runId, String status, String errorCode, long input, long output) {
        jdbc.update("""
                UPDATE agent_runs SET status = ?, error_code = ?, input_tokens = ?, output_tokens = ?, cost_usd = ?,
                       finished_at = ? WHERE id = ?
                """, status, errorCode, input, output, budget.cost(input, output), Timestamp.from(clock.instant()), runId);
    }

    /**
     * Records a back-office run (a memo) from the agent's JSON answer: its steps, tokens and outcome. Back-office runs
     * have no user, so they never count against a customer's budget. Returns the run id.
     */
    public UUID recordBackOfficeRun(String kind, JsonNode res, String errorCode) {
        UUID runId = jdbc.queryForObject("INSERT INTO agent_runs (kind) VALUES (?) RETURNING id", UUID.class, kind);
        if (res != null) {
            steps(runId, res.path("steps"));
        }
        long in = res == null ? 0 : res.path("usage").path("inputTokens").asLong(0);
        long out = res == null ? 0 : res.path("usage").path("outputTokens").asLong(0);
        finishRun(runId, errorCode == null ? "OK" : "ERROR", errorCode == null ? null : clip(errorCode, 30), in, out);
        return runId;
    }

    /** Whether the AI layer is on (back-office jobs simply wait while it's off). */
    public boolean enabled() {
        return flags.isEnabled(FLAG);
    }

    /** Records the steps an agent returned in a non-streamed answer (Autopilot compile). */
    void steps(UUID runId, JsonNode steps) {
        int seq = 0;
        for (JsonNode s : steps) {
            step(runId, ++seq, s);
        }
    }

    /** The user's own Autopilot run as plain facts; someone else's or unknown → 404. */
    private String context(UUID userId, Context context) {
        if (!"AUTOPILOT_RUN".equals(context.type()) || context.id() == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "context.type", "must be AUTOPILOT_RUN with an id");
        }
        List<String> run = jdbc.query("""
                SELECT ru.name, r.trigger_summary, r.outcome, r.rule_version, r.started_at FROM autopilot_runs r
                  JOIN autopilot_rules ru ON ru.id = r.rule_id WHERE r.id = ? AND r.user_id = ?
                """, (rs, i) -> "Autopilot rule \"" + rs.getString(1) + "\" (version " + rs.getInt(4) + ") ran at "
                + rs.getTimestamp(5).toInstant() + " because: " + rs.getString(2) + ". Outcome: "
                + (rs.getString(3) == null ? "still running" : rs.getString(3)) + ".", context.id(), userId);
        if (run.isEmpty()) {
            throw ApiException.notFound("Run");
        }
        List<String> actions = jdbc.query("SELECT idx, type, amount, status, error FROM autopilot_run_actions "
                + "WHERE run_id = ? ORDER BY idx", (rs, i) -> "action " + (rs.getInt(1) + 1) + " " + rs.getString(2)
                + (rs.getBigDecimal(3) == null ? "" : " ₹" + Money.toJson(rs.getBigDecimal(3)))
                + " " + rs.getString(4) + (rs.getString(5) == null ? "" : " (" + rs.getString(5) + ")"), context.id());
        return clip(run.getFirst() + (actions.isEmpty() ? "" : " " + String.join("; ", actions) + "."), 1200);
    }

    // ------------------------------------------------------------------ Autopilot authoring

    /** The Planner's draft: a rule to validate (never trusted as is) and the model's warnings. */
    public record Draft(JsonNode rule, List<String> warnings, UUID runId) {
    }

    /**
     * F-AUTO-2: plain language → a DSL draft from the Planner. The caller validates it like any rule; the model's
     * output is never run or rendered as is. Same flag, limits and budgets as a chat turn.
     */
    public Draft compile(UUID userId, String text) {
        requireEnabled();
        rateLimiter.acquire(TURN_LIMIT, userId);
        budget.requireAvailable(userId);
        // Not part of a conversation: the token's conversation id matches no row, so it can't attach intents anywhere.
        UUID scratch = UUID.randomUUID();
        UUID runId = jdbc.queryForObject("INSERT INTO agent_runs (user_id, kind) VALUES (?, 'COMPILE') RETURNING id",
                UUID.class, userId);
        JsonNode res;
        try {
            res = agent.compile(runId, scratch, jwt.issueAgentToken(userId, scratch), text);
        } catch (ApiException e) {
            finishRun(runId, "ERROR", e.code().name(), 0, 0);
            throw e;
        }
        steps(runId, res.path("steps"));
        long in = res.path("usage").path("inputTokens").asLong(0);
        long out = res.path("usage").path("outputTokens").asLong(0);
        String error = res.path("error").path("code").asString(null);
        finishRun(runId, error == null ? "OK" : "ERROR", error == null ? null : clip(error, 30), in, out);
        if (error != null || !res.path("rule").isObject()) {
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI_BUSY".equals(error) ? errorMessage(error)
                    : "The assistant couldn't turn that into a rule; try the form");
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode w : res.path("warnings")) {
            String s = w.asString("");
            if (!s.isEmpty() && s.matches(TextRules.NO_CONTROL_CHARS) && warnings.size() < 5) {
                warnings.add(clip(s, 200));
            }
        }
        return new Draft(res.path("rule"), warnings, runId);
    }

    // ------------------------------------------------------------------ traces

    /** The customer's view: their own runs, with the step details left out. */
    public TraceDto trace(UUID userId, UUID runId) {
        return trace(runId, userId, false);
    }

    /** The admin's view: any run, with the (already redacted) details. */
    public TraceDto adminTrace(UUID runId) {
        return trace(runId, null, true);
    }

    private TraceDto trace(UUID runId, UUID userId, boolean details) {
        List<Map<String, Object>> run = jdbc.queryForList("SELECT started_at, finished_at, cost_usd, user_id FROM "
                + "agent_runs WHERE id = ?", runId);
        if (run.isEmpty() || (userId != null && !userId.equals(run.getFirst().get("user_id")))) {
            throw ApiException.notFound("Run");
        }
        Instant started = ((Timestamp) run.getFirst().get("started_at")).toInstant();
        Timestamp finished = (Timestamp) run.getFirst().get("finished_at");
        List<StepDto> steps = jdbc.query("SELECT agent, kind, name, status, latency_ms, input_tokens, output_tokens, "
                        + "detail FROM agent_steps WHERE run_id = ? ORDER BY seq",
                (rs, i) -> new StepDto(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                        rs.getInt(6), rs.getInt(7), details ? rs.getString(8) : null), runId);
        return new TraceDto(runId, started, finished == null ? 0 : Duration.between(started, finished.toInstant()).toMillis(),
                ((BigDecimal) run.getFirst().get("cost_usd")).setScale(2, RoundingMode.HALF_UP), steps);
    }

    // ------------------------------------------------------------------ retention

    /** F-AGT-7: conversations untouched for 30 days go, with their messages and traces; so do old compile runs. */
    @Scheduled(cron = "${qg.copilot.retention-cron:0 15 3 * * *}", zone = "Asia/Kolkata")
    void purge() {
        try {
            Timestamp cutoff = Timestamp.from(clock.instant().minus(RETENTION));
            int c = jdbc.update("DELETE FROM copilot_conversations WHERE updated_at < ?", cutoff);
            int r = jdbc.update("DELETE FROM agent_runs WHERE started_at < ?", cutoff);
            if (c + r > 0) {
                log.info("Copilot retention removed {} conversation(s) and {} run(s)", c, r);
            }
        } catch (RuntimeException e) {
            log.error("Copilot retention failed", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    public void requireEnabled() {
        if (!flags.isEnabled(FLAG)) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "The assistant is switched off");
        }
    }

    private ConversationDto conversation(UUID userId, UUID id) {
        return jdbc.query("SELECT id, title, created_at, updated_at FROM copilot_conversations WHERE id = ? AND user_id = ?",
                (rs, i) -> new ConversationDto(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()), id, userId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    /** A citation the UI can resolve itself: known type, short id and label, no control characters. */
    private static Citation citation(JsonNode c) {
        String type = c.path("type").asString("");
        String id = c.path("id").asString("");
        String label = c.path("label").asString("");
        if (!CITATION_TYPES.contains(type) || id.isEmpty() || id.length() > 64 || !id.matches("^[A-Za-z0-9._:-]+$")
                || label.isEmpty() || !label.matches(TextRules.NO_CONTROL_CHARS)) {
            return null;
        }
        return new Citation(type, id, clip(label, 80));
    }

    static String title(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        return flat.length() <= 60 ? flat : flat.substring(0, 57) + "…";
    }

    private static String errorMessage(String code) {
        return switch (code) {
            case "AI_BUSY" -> "The assistant is busy right now; try again in a minute.";
            case "TOOL_LIMIT" -> "That needed too many steps; try asking something narrower.";
            case "AI_UNAVAILABLE" -> "The assistant isn't available right now.";
            default -> "Something went wrong while answering.";
        };
    }

    private static String joinIds(List<UUID> ids) {
        return String.join(",", ids.stream().map(UUID::toString).toList());
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
