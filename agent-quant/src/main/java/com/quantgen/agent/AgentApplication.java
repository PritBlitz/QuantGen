package com.quantgen.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * agent-quant: the QuantGen copilot (PRD 11.4.5). Stateless (D-045): the core sends each turn with a delegated token
 * and the conversation history, and stores what comes back. The model is reached through an OpenAI-compatible API
 * (D-044); agents can only read and propose intents through the core, never move money.
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
