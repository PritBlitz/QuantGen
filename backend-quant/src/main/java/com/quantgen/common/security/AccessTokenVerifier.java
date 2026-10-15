package com.quantgen.common.security;

import java.util.Optional;

/** Verifies a bearer access token. Implemented by the auth module's JwtService. */
public interface AccessTokenVerifier {

    /** The caller if the token is valid (signature, issuer, expiry); empty otherwise. Never throws. */
    Optional<AuthUser> verify(String token);
}
