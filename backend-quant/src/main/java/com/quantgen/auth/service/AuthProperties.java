package com.quantgen.auth.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth settings ({@code qg.jwt.*}, {@code qg.auth.*}). The JWT secret has no default here: it comes from
 * {@code QG_JWT_SECRET} (prod) or the dev/test profile, and startup fails if it is missing or weak.
 */
@ConfigurationProperties(prefix = "qg")
public record AuthProperties(Jwt jwt, Auth auth) {

    public AuthProperties {
        jwt = jwt == null ? new Jwt(null, null, null) : jwt;
        auth = auth == null ? new Auth(null, null, null, null) : auth;
    }

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl) {

        public Jwt {
            accessTtl = accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
            refreshTtl = refreshTtl == null ? Duration.ofDays(7) : refreshTtl;
        }
    }

    public record Auth(Boolean cookieSecure, Integer maxFailedLogins, Duration failedLoginWindow,
                       Duration lockoutDuration) {

        public Auth {
            cookieSecure = cookieSecure == null || cookieSecure;
            maxFailedLogins = maxFailedLogins == null ? 5 : maxFailedLogins;
            failedLoginWindow = failedLoginWindow == null ? Duration.ofMinutes(15) : failedLoginWindow;
            lockoutDuration = lockoutDuration == null ? Duration.ofMinutes(15) : lockoutDuration;
        }
    }
}
