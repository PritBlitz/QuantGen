package com.quantgen.auth.security.passkey;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Relying-party settings. {@code rpId} must be the registrable domain the SPA is served from (or
 * {@code localhost} in dev): a passkey created for one RP ID never works for another. Allowed origins are the
 * CORS origins ({@code qg.cors.allowed-origins}), so there is a single list to keep right.
 */
@ConfigurationProperties(prefix = "qg.webauthn")
public record WebAuthnProperties(String rpId, String rpName) {

    public WebAuthnProperties {
        rpId = rpId == null || rpId.isBlank() ? "localhost" : rpId;
        rpName = rpName == null || rpName.isBlank() ? "QuantGen" : rpName;
    }
}
