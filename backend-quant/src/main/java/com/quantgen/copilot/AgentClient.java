package com.quantgen.copilot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The core's side of the internal API of {@code agent-quant} (D-045). Every call carries the shared secret and a
 * delegated token for one conversation; the agent calls the core back with that token only. Nothing here ever
 * logs a token, the secret or a message's text.
 */
@Component
public class AgentClient {

    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);
    static final String SECRET_HEADER = "X-Agent-Secret";

    /** One line of history sent with a turn (the newest last). */
    public record HistoryItem(String role, String text) {
    }

    /** An event of the agent's stream: its type and JSON payload. */
    public record AgentEvent(String type, JsonNode data) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final JsonMapper json;
    private final String baseUrl;
    private final String secret;
    private final Duration turnTimeout;

    AgentClient(JsonMapper json, @Value("${qg.agent.url:http://localhost:8090}") String baseUrl,
                @Value("${qg.agent.secret:}") String secret,
                @Value("${qg.agent.turn-timeout:PT90S}") Duration turnTimeout) {
        this.json = json;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
        this.turnTimeout = turnTimeout;
    }

    /** An open turn: events are read (blocking) by {@link #pump}. Closing it abandons the agent's stream. */
    public static final class Turn implements AutoCloseable {
        private final Stream<String> lines;
        private final Iterator<String> it;
        private final JsonMapper json;

        Turn(Stream<String> lines, JsonMapper json) {
            this.lines = lines;
            this.it = lines.iterator();
            this.json = json;
        }

        /** Reads SSE frames ({@code event:}/{@code data:} lines, blank line ends a frame) until the stream ends. */
        public void pump(Consumer<AgentEvent> onEvent) {
            String type = null;
            StringBuilder data = new StringBuilder();
            while (it.hasNext()) {
                String line = it.next();
                if (line.isEmpty()) {
                    if (type != null) {
                        onEvent.accept(new AgentEvent(type, json.readTree(data.isEmpty() ? "{}" : data.toString())));
                    }
                    type = null;
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    type = line.substring(6).strip();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).strip());
                }
            }
            if (type != null) {
                onEvent.accept(new AgentEvent(type, json.readTree(data.isEmpty() ? "{}" : data.toString())));
            }
        }

        @Override
        public void close() {
            lines.close();
        }
    }

    /**
     * Starts a chat turn. Fails before any streaming with AI_UNAVAILABLE when the agent is down, has no provider
     * configured (503) or rejects the call, so the caller can answer with a normal JSON error.
     */
    public Turn startTurn(UUID runId, UUID conversationId, String token, List<HistoryItem> history, String text) {
        Map<String, Object> body = Map.of("runId", runId.toString(), "conversationId", conversationId.toString(),
                "token", token, "history", history, "text", text);
        HttpRequest req = request("/internal/v1/turns", body).header("Accept", "text/event-stream")
                .timeout(turnTimeout).build();
        try {
            HttpResponse<Stream<String>> res = http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (res.statusCode() != 200) {
                res.body().close();
                throw unavailable(res.statusCode());
            }
            return new Turn(res.body(), json);
        } catch (IOException e) {
            log.warn("agent-quant unreachable: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't available right now");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't available right now");
        }
    }

    /** Autopilot authoring: the Planner turns text into a DSL draft. Returns the agent's JSON answer. */
    public JsonNode compile(UUID runId, UUID conversationId, String token, String text) {
        HttpRequest req = request("/internal/v1/compile", Map.of("runId", runId.toString(),
                "conversationId", conversationId.toString(), "token", token, "text", text))
                .timeout(turnTimeout).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw unavailable(res.statusCode());
            }
            return json.readTree(res.body());
        } catch (IOException e) {
            log.warn("agent-quant unreachable: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't available right now");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't available right now");
        }
    }

    /** A back-office job (memo): JSON in, JSON out, no delegated token (the core sends the facts itself). */
    public JsonNode backOffice(String path, Object body) {
        HttpRequest req = request(path, body).timeout(turnTimeout).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw unavailable(res.statusCode());
            }
            return json.readTree(res.body());
        } catch (IOException e) {
            log.warn("agent-quant unreachable: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't available right now");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't available right now");
        }
    }

    private HttpRequest.Builder request(String path, Object body) {
        if (secret.isBlank()) {
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "The assistant isn't set up on this server");
        }
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header(SECRET_HEADER, secret)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    }

    private static ApiException unavailable(int status) {
        log.warn("agent-quant answered {}", status);
        return new ApiException(ErrorCode.AI_UNAVAILABLE, status == 503
                ? "The assistant isn't set up on this server" : "The assistant isn't available right now");
    }
}
