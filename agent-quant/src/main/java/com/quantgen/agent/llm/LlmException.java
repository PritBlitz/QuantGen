package com.quantgen.agent.llm;

/** A failed model call. {@code busy} means the provider rate-limited us (free tier): say so, never retry blindly. */
public class LlmException extends RuntimeException {

    private final boolean busy;

    public LlmException(String message, boolean busy) {
        super(message, null, false, false);
        this.busy = busy;
    }

    public boolean busy() {
        return busy;
    }
}
