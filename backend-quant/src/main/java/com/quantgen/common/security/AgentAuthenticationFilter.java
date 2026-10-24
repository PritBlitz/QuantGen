package com.quantgen.common.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.quantgen.common.error.ApiErrorWriter;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.security.AgentTokenVerifier.AgentPrincipal;

/**
 * Authenticates delegated agent tokens (D-022, {@code aud=agent}) and enforces their scope here, deny by default:
 * the 🤖 read routes and the propose/check routes below. Approving an intent, moving money, admin and anything else
 * is 403, whatever the token says. The conversation the token was minted for is put on the request.
 *
 * <p>Not a {@code @Component}: it is added to the security chain only, after {@link JwtAuthenticationFilter}.
 */
public class AgentAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the conversation id of the agent token that authenticated the request. */
    public static final String CONVERSATION_ATTRIBUTE = "qg.agentConversationId";
    private static final String BEARER = "Bearer ";
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /** GET routes an agent may read: a personal access token's, plus what the specialists need to plan. */
    static final List<String> READ = readRoutes();
    /** POST routes that create nothing but an intent, or only check something. */
    static final List<String> POST = List.of("/api/v1/intents", "/api/v1/autopilot/validate",
            "/api/v1/autopilot/simulate", "/api/v1/loans/quote");

    private static List<String> readRoutes() {
        List<String> r = new ArrayList<>(PatAuthenticationFilter.READ);
        r.addAll(List.of("/api/v1/beneficiaries", "/api/v1/billers", "/api/v1/saved-billers",
                "/api/v1/autopilot/rules", "/api/v1/autopilot/rules/*", "/api/v1/autopilot/templates"));
        return List.copyOf(r);
    }

    private final AgentTokenVerifier verifier;
    private final ApiErrorWriter errors;

    public AgentAuthenticationFilter(AgentTokenVerifier verifier, ApiErrorWriter errors) {
        this.verifier = verifier;
        this.errors = errors;
    }

    static boolean allows(String method, String path) {
        List<String> routes = "GET".equals(method) ? READ : "POST".equals(method) ? POST : List.of();
        return routes.stream().anyMatch(p -> PATHS.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring(BEARER.length()).trim();
        Optional<AgentPrincipal> found = raw.startsWith(PersonalTokenAuthenticator.PREFIX) ? Optional.empty()
                : verifier.verifyAgent(raw);
        if (found.isEmpty()) {
            chain.doFilter(request, response); // a user's token (or nothing valid): the other filters decide
            return;
        }
        AgentPrincipal agent = found.get();
        if (!allows(request.getMethod(), request.getRequestURI())) {
            errors.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Agent tokens can't be used for this request");
            return;
        }
        request.setAttribute(CONVERSATION_ATTRIBUTE, agent.conversationId());
        AuthUser user = new AuthUser(agent.userId(), AuthUser.ROLE_CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + AuthUser.ROLE_CUSTOMER))));
        MDC.put(JwtAuthenticationFilter.MDC_USER_ID, agent.userId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(JwtAuthenticationFilter.MDC_USER_ID);
        }
    }
}
