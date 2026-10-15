package com.quantgen.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** OpenAPI at /v3/api-docs, Swagger UI at /swagger-ui.html (PRD §6). */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER = "bearerAuth";

    @Bean
    OpenAPI quantGenOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("QuantGen API")
                        .version("v1")
                        .description("Banking, ledger, trading and loans. Money is always a JSON string; "
                                + "ids are UUIDs; timestamps are ISO-8601 UTC. Contract: PRD §7."))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
