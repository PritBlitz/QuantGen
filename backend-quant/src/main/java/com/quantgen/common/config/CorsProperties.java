package com.quantgen.common.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** CORS allowlist (comma-separated {@code QG_CORS_ORIGINS}). */
@ConfigurationProperties(prefix = "qg.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
