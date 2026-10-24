package com.quantgen.agent;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Configuration from the environment. The provider key and the shared secret are never logged. */
@Component
public class Settings {

    private final String aiBaseUrl;
    private final String aiModel;
    private final String aiApiKey;
    private final String secret;
    private final String coreUrl;
    private final int maxToolCalls;
    private final Duration llmTimeout;

    Settings(@Value("${qg.ai.base-url}") String aiBaseUrl, @Value("${qg.ai.model}") String aiModel,
             @Value("${qg.ai.api-key:}") String aiApiKey, @Value("${qg.agent.secret:}") String secret,
             @Value("${qg.core.url}") String coreUrl, @Value("${qg.ai.max-tool-calls:10}") int maxToolCalls,
             @Value("${qg.ai.timeout:PT40S}") Duration llmTimeout) {
        this.aiBaseUrl = aiBaseUrl.replaceAll("/+$", "");
        this.aiModel = aiModel;
        this.aiApiKey = aiApiKey;
        this.secret = secret;
        this.coreUrl = coreUrl.replaceAll("/+$", "");
        this.maxToolCalls = maxToolCalls;
        this.llmTimeout = llmTimeout;
    }

    public String aiBaseUrl() {
        return aiBaseUrl;
    }

    public String aiModel() {
        return aiModel;
    }

    public String aiApiKey() {
        return aiApiKey;
    }

    public String secret() {
        return secret;
    }

    public String coreUrl() {
        return coreUrl;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public Duration llmTimeout() {
        return llmTimeout;
    }

    /** Without a key there is no assistant; turns answer 503 and the core says "not set up". */
    public boolean configured() {
        return !aiApiKey.isBlank() && !secret.isBlank();
    }
}
