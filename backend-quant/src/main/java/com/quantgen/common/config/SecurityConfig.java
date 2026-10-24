package com.quantgen.common.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.quantgen.common.error.ApiErrorWriter;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.security.AccessTokenVerifier;
import com.quantgen.common.security.AgentAuthenticationFilter;
import com.quantgen.common.security.AgentTokenVerifier;
import com.quantgen.common.security.JwtAuthenticationFilter;
import com.quantgen.common.security.PatAuthenticationFilter;
import com.quantgen.common.security.PersonalTokenAuthenticator;

/**
 * Stateless, deny-by-default security (ARCHITECTURE §3 request pipeline).
 *
 * <p>CSRF protection is disabled because the API authenticates with a bearer header, which browsers
 * never attach automatically. The only cookie (the refresh token) is {@code SameSite=Strict} and scoped
 * to {@code /api/v1/auth}.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String[] PUBLIC_DOCS = {"/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"};

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ApiErrorWriter errorWriter,
                                           AccessTokenVerifier tokenVerifier, PersonalTokenAuthenticator personalTokens,
                                           AgentTokenVerifier agentTokens)
            throws Exception {
        http
                .addFilterBefore(new JwtAuthenticationFilter(tokenVerifier), UsernamePasswordAuthenticationFilter.class)
                // Personal access tokens (qg_pat_…) are checked after JWTs and confined to their read routes.
                .addFilterAfter(new PatAuthenticationFilter(personalTokens, errorWriter), JwtAuthenticationFilter.class)
                // Delegated agent tokens (aud=agent) are never accepted as access tokens; this confines them to 🤖 routes.
                .addFilterAfter(new AgentAuthenticationFilter(agentTokens, errorWriter), PatAuthenticationFilter.class)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> {
                })
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h
                        .frameOptions(f -> f.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> errorWriter.write(req, res, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler((req, res, ex) -> errorWriter.write(req, res, ErrorCode.FORBIDDEN)))
                .authorizeHttpRequests(auth -> auth
                        // The async re-dispatch that completes an SSE response (copilot) was authorized as a request.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        // Metrics for Prometheus: private/loopback callers only. nginx never proxies /actuator, and
                        // forward-headers-strategy=native makes remoteAddr the real client behind a trusted proxy.
                        .requestMatchers("/actuator/prometheus").access((authn, ctx) ->
                                new AuthorizationDecision(isInternal(ctx.getRequest().getRemoteAddr())))
                        .requestMatchers(PUBLIC_DOCS).permitAll()
                        // Step-up lives under /auth but is for signed-in users only. Listed before the public rule.
                        .requestMatchers("/api/v1/auth/step-up/**").authenticated()
                        // Passkey sign-in is public; registering, listing and removing passkeys is not.
                        .requestMatchers("/api/v1/auth/passkeys/login", "/api/v1/auth/passkeys/login/**").permitAll()
                        .requestMatchers("/api/v1/auth/passkeys", "/api/v1/auth/passkeys/**").authenticated()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Razorpay calls this without a JWT; TopupService verifies its HMAC signature instead.
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/razorpay").permitAll()
                        // The STOMP CONNECT frame carries the JWT and is validated by a ChannelInterceptor.
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Proof of Balance roots are published to everyone, signed in or not (PRD 12.10).
                        .requestMatchers(HttpMethod.GET, "/api/v1/public/proofs/roots").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    static boolean isInternal(String remoteAddr) {
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            return addr.isLoopbackAddress() || addr.isSiteLocalAddress();
        } catch (UnknownHostException | RuntimeException e) {
            return false;
        }
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(props.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", "X-Request-Id",
                "X-Step-Up-Token"));
        config.setExposedHeaders(List.of("X-Request-Id", "Retry-After", "Content-Disposition", "Location",
                "Idempotent-Replayed"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/ws/**", config);
        return source;
    }
}
