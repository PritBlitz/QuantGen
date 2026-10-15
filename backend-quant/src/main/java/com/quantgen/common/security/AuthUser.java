package com.quantgen.common.security;

import java.util.UUID;

/** The authenticated caller, taken from a verified access token. */
public record AuthUser(UUID id, String role) {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }
}
