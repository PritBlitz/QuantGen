package com.quantgen.agent.tools;

import java.util.Map;

import com.quantgen.agent.run.Turn;

import tools.jackson.databind.JsonNode;

/** A function the model may call: a JSON-schema signature and a handler that returns text for the model. */
public record Tool(String name, String description, Map<String, Object> parameters, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        String call(JsonNode args, Turn turn);
    }

    /** The OpenAI-style definition sent to the provider. */
    public Map<String, Object> definition() {
        return Map.of("type", "function", "function", Map.of("name", name, "description", description,
                "parameters", parameters));
    }
}
