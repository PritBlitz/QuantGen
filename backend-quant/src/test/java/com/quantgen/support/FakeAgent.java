package com.quantgen.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for agent-quant's internal API on the port the test profile points at. A test sets a script: a
 * function of the request body (with the delegated token in it) that returns the raw answer to send.
 */
public final class FakeAgent implements AutoCloseable {

    public static final int PORT = 18091;

    /** What to answer: an HTTP status and a body (an SSE text for turns, JSON for compile). */
    public record Answer(int status, String contentType, String body) {

        public static Answer sse(String body) {
            return new Answer(200, "text/event-stream", body);
        }

        public static Answer json(String body) {
            return new Answer(200, "application/json", body);
        }
    }

    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private volatile Function<JsonNode, Answer> turn = req -> new Answer(503, "application/json", "{}");
    private volatile Function<JsonNode, Answer> compile = req -> new Answer(503, "application/json", "{}");
    private volatile Function<JsonNode, Answer> memo = req -> new Answer(503, "application/json", "{}");
    private volatile Function<JsonNode, Answer> underwriting = req -> new Answer(503, "application/json", "{}");
    private volatile String lastSecret;
    private volatile JsonNode lastMemoRequest;

    public FakeAgent() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", PORT), 0);
        server.createContext("/internal/v1/turns", ex -> answer(ex, turn));
        server.createContext("/internal/v1/compile", ex -> answer(ex, compile));
        server.createContext("/internal/v1/memos/fraud", ex -> answer(ex, req -> {
            lastMemoRequest = req;
            return memo.apply(req);
        }));
        server.createContext("/internal/v1/memos/underwriting", ex -> answer(ex, req -> {
            lastMemoRequest = req;
            return underwriting.apply(req);
        }));
        server.start();
    }

    public void onTurn(Function<JsonNode, Answer> script) {
        this.turn = script;
    }

    public void onCompile(Function<JsonNode, Answer> script) {
        this.compile = script;
    }

    public void onFraudMemo(Function<JsonNode, Answer> script) {
        this.memo = script;
    }

    public void onUnderwritingMemo(Function<JsonNode, Answer> script) {
        this.underwriting = script;
    }

    public JsonNode lastMemoRequest() {
        return lastMemoRequest;
    }

    public String lastSecret() {
        return lastSecret;
    }

    /** One SSE frame. */
    public static String event(String type, String data) {
        return "event: " + type + "\ndata: " + data + "\n\n";
    }

    private void answer(HttpExchange ex, Function<JsonNode, Answer> script) throws IOException {
        lastSecret = ex.getRequestHeaders().getFirst("X-Agent-Secret");
        JsonNode body = json.readTree(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Answer a;
        try {
            a = script.apply(body);
        } catch (RuntimeException e) {
            a = new Answer(500, "application/json", "{}");
        }
        byte[] bytes = a.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", a.contentType());
        ex.sendResponseHeaders(a.status(), bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
