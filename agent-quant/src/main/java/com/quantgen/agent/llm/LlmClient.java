package com.quantgen.agent.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.quantgen.agent.Settings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One call to an OpenAI-compatible {@code /chat/completions} endpoint with tool calling (Gemini's free tier by
 * default, D-044; Groq or Ollama by config). Non-streamed: tool calls need the whole message anyway.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** The assistant message ({@code content}, {@code tool_calls}) and the tokens it cost. */
    public record Reply(JsonNode message, int inputTokens, int outputTokens) {
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Settings settings;
    private final JsonMapper json;

    LlmClient(Settings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /**
     * @param tools OpenAI-style tool definitions, or empty
     * @param jsonOutput ask for a JSON object as the answer (Autopilot compile)
     */
    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, boolean jsonOutput) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.aiModel());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        if (!tools.isEmpty()) {
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        if (jsonOutput) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(settings.aiBaseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + settings.aiApiKey())
                .timeout(settings.llmTimeout())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new LlmException("provider unreachable: " + e.getClass().getSimpleName(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("interrupted", false);
        }
        if (res.statusCode() == 429) {
            throw new LlmException("provider rate limit", true);
        }
        if (res.statusCode() != 200) {
            // The body may echo the request; log only the status.
            log.warn("LLM provider answered {}", res.statusCode());
            throw new LlmException("provider answered " + res.statusCode(), false);
        }
        JsonNode root = json.readTree(res.body());
        JsonNode message = root.path("choices").path(0).path("message");
        if (!message.isObject()) {
            throw new LlmException("no message in the answer", false);
        }
        return new Reply(message, root.path("usage").path("prompt_tokens").asInt(0),
                root.path("usage").path("completion_tokens").asInt(0));
    }
}
