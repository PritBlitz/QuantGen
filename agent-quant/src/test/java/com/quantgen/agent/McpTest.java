package com.quantgen.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F-MCP-1: the MCP endpoint against a fake core. The token goes through to the core; nothing else is accepted. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "qg.agent.secret=s3cret"})
class McpTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PAT = "qg_pat_" + "a".repeat(43);
    static final List<String> coreCalls = new CopyOnWriteArrayList<>();
    static HttpServer core;

    static {
        try {
            core = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            core.createContext("/", ex -> {
                coreCalls.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                        + ex.getRequestHeaders().getFirst("Authorization"));
                String path = ex.getRequestURI().getPath();
                if (path.equals("/api/v1/accounts")) {
                    reply(ex, 200, "[{\"id\":\"3f2c1a9e-0b1d-4c2e-9f3a-1234567890ab\",\"type\":\"SAVINGS\","
                            + "\"accountNumber\":\"123456789012\",\"balance\":\"5000.00\"}]");
                } else if (path.equals("/api/v1/intents")) {
                    reply(ex, 201, "{\"id\":\"9a8b7c6d-0000-4000-8000-000000000001\",\"summary\":{\"title\":\"Send ₹500.00\"}}");
                } else {
                    reply(ex, 403, "{\"code\":\"FORBIDDEN\",\"message\":\"Personal access tokens can't be used for this request\"}");
                }
            });
            core.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry r) {
        r.add("qg.core.url", () -> "http://127.0.0.1:" + core.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        core.stop(0);
    }

    @LocalServerPort
    int port;

    @BeforeEach
    void reset() {
        coreCalls.clear();
    }

    private HttpResponse<String> mcp(String auth, String origin, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode rpc(String method, String params) throws Exception {
        HttpResponse<String> res = mcp("Bearer " + PAT, null,
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}");
        assertThat(res.statusCode()).isEqualTo(200);
        return JSON.readTree(res.body());
    }

    @Test
    void initializeListAndCallAReadWithTheTokenPassedThroughAndMasked() throws Exception {
        JsonNode init = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"claude-desktop\",\"version\":\"1\"}}");
        assertThat(init.path("id").asInt()).isEqualTo(7);
        assertThat(init.path("result").path("protocolVersion").asString()).isEqualTo("2025-06-18");
        assertThat(init.path("result").path("capabilities").has("tools")).isTrue();
        assertThat(mcp("Bearer " + PAT, null, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .statusCode()).isEqualTo(202);

        JsonNode list = rpc("tools/list", "{}");
        List<String> names = list.path("result").path("tools").valueStream().map(t -> t.path("name").asString()).toList();
        assertThat(names).hasSize(16).contains("get_accounts", "propose_intent")
                .doesNotContain("loan_quote", "validate_rule", "get_beneficiaries"); // a token can't reach these
        assertThat(list.path("result").path("tools").get(0).has("inputSchema")).isTrue();

        JsonNode call = rpc("tools/call", "{\"name\":\"get_accounts\",\"arguments\":{}}");
        String text = call.path("result").path("content").get(0).path("text").asString();
        assertThat(text).startsWith("<bank_data>").contains("••••9012").doesNotContain("123456789012");
        assertThat(call.path("result").path("isError").asBoolean()).isFalse();
        assertThat(coreCalls).containsExactly("GET /api/v1/accounts Bearer " + PAT);
    }

    @Test
    void aProposalIsOnlyAnIntentAndACoreRefusalIsAToolError() throws Exception {
        JsonNode call = rpc("tools/call", "{\"name\":\"propose_intent\",\"arguments\":{\"kind\":\"TRANSFER\","
                + "\"payload\":{\"toHandle\":\"aarav@qg\",\"amount\":\"500.00\"},\"rationale\":\"Rent\"}}");
        assertThat(call.path("result").path("content").get(0).path("text").asString()).contains("approval card");
        // The drain guard reads the balance first (₹500 of ₹5,000 is fine), both with the client's token.
        assertThat(coreCalls).containsExactly("GET /api/v1/accounts Bearer " + PAT, "POST /api/v1/intents Bearer " + PAT);

        JsonNode refused = rpc("tools/call", "{\"name\":\"get_spending\",\"arguments\":{}}");
        assertThat(refused.path("result").path("isError").asBoolean()).isTrue();
        assertThat(rpc("tools/call", "{\"name\":\"transfer_money\",\"arguments\":{}}").path("error").path("code")
                .asInt()).isEqualTo(-32602);
        assertThat(rpc("resources/list", "{}").path("error").path("code").asInt()).isEqualTo(-32601);
    }

    @Test
    void onlyAPersonalAccessTokenWithoutABrowserOriginGetsIn() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        assertThat(mcp(null, null, ping).statusCode()).isEqualTo(401);
        assertThat(mcp("Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig", null, ping).statusCode()).isEqualTo(401); // a JWT
        assertThat(mcp("Bearer s3cret", null, ping).statusCode()).isEqualTo(401); // the shared secret
        assertThat(mcp("Bearer " + PAT, "https://evil.example", ping).statusCode()).isEqualTo(403);
        assertThat(mcp("Bearer " + PAT, null, ping).statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(mcp("Bearer " + PAT, null, "[" + ping + "]").body()).path("error").path("code")
                .asInt()).isEqualTo(-32600); // no batches
        assertThat(coreCalls).isEmpty();
    }
}
