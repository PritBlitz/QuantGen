package com.quantgen.common.security;

import java.util.Optional;
import java.util.UUID;

/**
 * Checks personal access tokens (PRD 12.12) for {@link PatAuthenticationFilter}. Implemented by the auth module,
 * which owns the token table.
 */
public interface PersonalTokenAuthenticator {

    /** Prefix of every personal access token, so it is never mistaken for a JWT. */
    String PREFIX = "qg_pat_";

    record TokenPrincipal(UUID tokenId, UUID userId, String scope) {
    }

    /** The live (unexpired, unrevoked) token, or empty. */
    Optional<TokenPrincipal> authenticate(String rawToken);

    /** Audits one use (F-MCP-2: every use) and bumps last_used_at; {@code allowed} false for a refused route. */
    void recordUse(TokenPrincipal principal, String method, String path, boolean allowed);

    /** Throws 429 when one token is used too fast. */
    void rateLimit(TokenPrincipal principal);
}
