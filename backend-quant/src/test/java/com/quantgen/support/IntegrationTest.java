package com.quantgen.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.quantgen.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base class for integration tests: the full app on a random port against a Testcontainers Postgres,
 * exercised over real HTTP so that filters, security and serialization are all in the loop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JsonMapper json;

    @Autowired
    protected JdbcTemplate jdbc;

    protected URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    protected HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30));
    }

    protected Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response, json);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + request.uri(), e);
        }
    }

    protected Response get(String path) {
        return send(request(path).GET().build());
    }

    protected Response get(String path, String bearer) {
        return send(request(path).header("Authorization", "Bearer " + bearer).GET().build());
    }

    protected Response postJson(String path, Object body, Map<String, String> headers) {
        HttpRequest.Builder builder = request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
        headers.forEach(builder::header);
        return send(builder.build());
    }

    public record Response(HttpResponse<String> raw, JsonMapper json) {

        public int status() {
            return raw.statusCode();
        }

        public String body() {
            return raw.body();
        }

        public JsonNode jsonBody() {
            return json.readTree(raw.body());
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }
}
