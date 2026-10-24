package com.quantgen.agent.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.quantgen.agent.Settings;

import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the core's 🤖 endpoints as the user, with the turn's delegated token (D-022). The core enforces ownership
 * and the agent allowlist; anything else answers 403 there, whatever the model asked for.
 */
@Component
public class CoreClient {

    /** The core's answer: status and body (JSON text, or the error body). */
    public record CoreResponse(int status, String body) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Settings settings;
    private final JsonMapper json;

    CoreClient(Settings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    public CoreResponse get(String token, String pathAndQuery) {
        return send(HttpRequest.newBuilder(URI.create(settings.coreUrl() + pathAndQuery)).GET(), token);
    }

    public CoreResponse post(String token, String path, Object body) {
        return send(HttpRequest.newBuilder(URI.create(settings.coreUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))), token);
    }

    private CoreResponse send(HttpRequest.Builder b, String token) {
        try {
            HttpResponse<String> res = http.send(b.header("Authorization", "Bearer " + token)
                    .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
            return new CoreResponse(res.statusCode(), res.body());
        } catch (IOException e) {
            return new CoreResponse(503, "{\"code\":\"CORE_UNREACHABLE\"}");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CoreResponse(503, "{\"code\":\"INTERRUPTED\"}");
        }
    }
}
