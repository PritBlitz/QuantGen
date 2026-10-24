package com.quantgen.agent.run;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One turn's state: the delegated token, where events go, the tool-call budget (F-AGT-4: 10 per turn), token
 * counts, and the ids the tools returned (a citation must point at one of them, so the model can't invent one).
 */
public final class Turn {

    /** Where the turn's events go (the SSE stream to the core). */
    public interface Sink {
        void emit(String type, Object data);
    }

    private static final Pattern UUID_RE = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SYMBOL_RE = Pattern.compile("\"symbol\"\s*:\s*\"([A-Z]{1,10})\"");

    final String token;
    final UUID conversationId;
    final Sink sink;
    final int maxToolCalls;
    int toolCalls;
    int inputTokens;
    int outputTokens;
    String agent = "CONCIERGE";
    private final Set<String> seen = new HashSet<>();

    public Turn(String token, UUID conversationId, Sink sink, int maxToolCalls) {
        this.token = token;
        this.conversationId = conversationId;
        this.sink = sink;
        this.maxToolCalls = maxToolCalls;
    }

    public String token() {
        return token;
    }

    public String agent() {
        return agent;
    }

    public Sink sink() {
        return sink;
    }

    public int inputTokens() {
        return inputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    /** Remembers the ids and symbols in a tool result, for citation checks. */
    void saw(String toolResult) {
        Matcher m = UUID_RE.matcher(toolResult);
        while (m.find()) {
            seen.add(m.group());
        }
        Matcher s = SYMBOL_RE.matcher(toolResult);
        while (s.find()) {
            seen.add(s.group(1));
        }
    }

    boolean wasSeen(String id) {
        return seen.contains(id);
    }
}
