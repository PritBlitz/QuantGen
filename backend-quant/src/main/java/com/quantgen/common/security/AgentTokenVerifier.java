package com.quantgen.common.security;

import java.util.Optional;
import java.util.UUID;

/** Verifies a delegated agent token (D-022). Implemented by the auth module's JwtService. */
public interface AgentTokenVerifier {

    /** The user and conversation a valid agent token acts for. */
    record AgentPrincipal(UUID userId, UUID conversationId) {
    }

    /** Empty unless the token is a valid, unexpired agent token. Never throws. */
    Optional<AgentPrincipal> verifyAgent(String token);
}
