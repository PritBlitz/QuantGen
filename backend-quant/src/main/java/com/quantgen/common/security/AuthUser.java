package com.quantgen.common.security;

import java.util.UUID;

/**
 * The authenticated caller, taken from a verified access token.
 *
 * @param sessionId the refresh-token family the token was issued for (the {@code sid} claim); null for tokens
 *                  that predate sessions. It identifies "this device" in the session list.
 */
public record AuthUser(UUID id, String role, UUID sessionId) {

    public AuthUser(UUID id, String role) {
        this(id, role, null);
    }

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
