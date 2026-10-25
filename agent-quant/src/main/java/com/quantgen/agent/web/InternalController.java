package com.quantgen.agent.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.quantgen.agent.Settings;
import com.quantgen.agent.llm.LlmException;
import com.quantgen.agent.run.AgentRunner;
import com.quantgen.agent.run.AgentRunner.HistoryItem;
import com.quantgen.agent.run.Turn;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The internal API the core calls (never the browser): a chat turn as an SSE stream, and Autopilot compile. Every
 * call must carry the shared secret. Without a provider key the service answers 503, and the core tells the user
 * the assistant isn't set up.
 */
@RestController
public class InternalController {

    private static final Logger log = LoggerFactory.getLogger(InternalController.class);
    static final String SECRET_HEADER = "X-Agent-Secret";

    public record TurnRequest(String runId, String conversationId, String token, List<HistoryItem> history,
                              String text) {
    }

    public record CompileRequest(String runId, String conversationId, String token, String text) {
    }

    private final Settings settings;
    private final AgentRunner runner;
    private final JsonMapper json;
    private final ExecutorService turns = Executors.newVirtualThreadPerTaskExecutor();

    InternalController(Settings settings, AgentRunner runner, JsonMapper json) {
        this.settings = settings;
        this.runner = runner;
        this.json = json;
    }

    @PostMapping(value = "/internal/v1/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> turn(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                           @RequestBody TurnRequest req) {
        HttpStatus refused = refuse(secret, req.token(), req.text());
        if (refused != null) {
            return ResponseEntity.status(refused).build();
        }
        SseEmitter emitter = new SseEmitter(180_000L);
        turns.submit(() -> {
            Turn turn = new Turn(req.token(), UUID.fromString(req.conversationId()), (type, data) -> send(emitter, type,
                    data), settings.maxToolCalls());
            try {
                runner.chat(turn, req.history() == null ? List.of() : req.history(), req.text());
                send(emitter, "done", Map.of("usage", Map.of("inputTokens", turn.inputTokens(),
                        "outputTokens", turn.outputTokens())));
            } catch (RuntimeException e) {
                String code = code(e);
                log.warn("Turn {} ended with {}", req.runId(), code); // no text, no token
                send(emitter, "done", Map.of("usage", Map.of("inputTokens", turn.inputTokens(),
                        "outputTokens", turn.outputTokens())));
                send(emitter, "error", Map.of("code", code, "message", code));
            }
            emitter.complete();
        });
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    @PostMapping("/internal/v1/compile")
    public ResponseEntity<Map<String, Object>> compile(@RequestHeader(value = SECRET_HEADER, required = false) String secret,
                                                       @RequestBody CompileRequest req) {
        HttpStatus refused = refuse(secret, req.token(), req.text());
        if (refused != null) {
            return ResponseEntity.status(refused).build();
        }
        List<Object> steps = new ArrayList<>();
        Turn turn = new Turn(req.token(), UUID.fromString(req.conversationId()), (type, data) -> {
            if ("step".equals(type)) {
                steps.add(data);
            }
        }, settings.maxToolCalls());
        Map<String, Object> usage;
        try {
            JsonNode draft = runner.compile(turn, req.text());
            usage = Map.of("inputTokens", turn.inputTokens(), "outputTokens", turn.outputTokens());
            List<String> warnings = new ArrayList<>();
            draft.path("warnings").forEach(w -> warnings.add(w.asString("")));
            return ResponseEntity.ok(Map.of("rule", draft.path("rule"), "warnings", warnings, "steps", steps,
                    "usage", usage));
        } catch (RuntimeException e) {
            usage = Map.of("inputTokens", turn.inputTokens(), "outputTokens", turn.outputTokens());
            return ResponseEntity.ok(Map.of("error", Map.of("code", code(e)), "steps", steps, "usage", usage));
        }
    }

    public record MemoRequest(JsonNode facts) {
    }

    /** F-RISK-3: an advisory memo for a held transfer. The core sends the masked facts; no token, no tools. */
    @PostMapping("/internal/v1/memos/fraud")
    public ResponseEntity<Map<String, Object>> fraudMemo(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody MemoRequest req) {
        return memo(secret, req, runner::fraudMemo);
    }

    /** F-RISK-5: an underwriting memo from the application and its PDF page texts (masked by the core). */
    @PostMapping("/internal/v1/memos/underwriting")
    public ResponseEntity<Map<String, Object>> underwritingMemo(
            @RequestHeader(value = SECRET_HEADER, required = false) String secret, @RequestBody MemoRequest req) {
        return memo(secret, req, runner::underwritingMemo);
    }

    private ResponseEntity<Map<String, Object>> memo(String secret, MemoRequest req,
                                                     java.util.function.BiFunction<Turn, JsonNode, JsonNode> writer) {
        HttpStatus refused = refuse(secret, "back-office", "memo");
        if (refused != null || req.facts() == null || !req.facts().isObject()) {
            return ResponseEntity.status(refused != null ? refused : HttpStatus.BAD_REQUEST).build();
        }
        List<Object> steps = new ArrayList<>();
        Turn turn = new Turn("", UUID.randomUUID(), (type, data) -> {
            if ("step".equals(type)) {
                steps.add(data);
            }
        }, 0);
        try {
            JsonNode memo = writer.apply(turn, req.facts());
            return ResponseEntity.ok(Map.of("memo", memo, "steps", steps, "usage", Map.of("inputTokens",
                    turn.inputTokens(), "outputTokens", turn.outputTokens())));
        } catch (RuntimeException e) {
            return ResponseEntity.ok(Map.of("error", Map.of("code", code(e)), "steps", steps, "usage",
                    Map.of("inputTokens", turn.inputTokens(), "outputTokens", turn.outputTokens())));
        }
    }

    /** 403 for a wrong secret, 503 when there's no provider, 400 for a malformed call; null when it may proceed. */
    private HttpStatus refuse(String secret, String token, String text) {
        if (settings.secret().isBlank() || secret == null || !MessageDigest.isEqual(
                secret.getBytes(StandardCharsets.UTF_8), settings.secret().getBytes(StandardCharsets.UTF_8))) {
            return HttpStatus.FORBIDDEN;
        }
        if (!settings.configured()) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        if (token == null || token.isBlank() || text == null || text.isBlank() || text.length() > 2000) {
            return HttpStatus.BAD_REQUEST;
        }
        return null;
    }

    private static String code(RuntimeException e) {
        if (e instanceof LlmException l) {
            return l.busy() ? "AI_BUSY" : "AI_UNAVAILABLE";
        }
        if (e instanceof AgentRunner.ToolLimitException) {
            return "TOOL_LIMIT";
        }
        return "INTERNAL";
    }

    private void send(SseEmitter emitter, String type, Object data) {
        try {
            emitter.send(SseEmitter.event().name(type).data(json.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            // The core went away; nothing to do.
        }
    }
}
