package com.quantgen.common.security;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates requests that carry {@code Authorization: Bearer <accessToken>}. An invalid or expired
 * token simply leaves the request unauthenticated; protected endpoints then answer 401.
 *
 * <p>Not a {@code @Component}: it is added to the security chain only, never to the servlet chain.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String MDC_USER_ID = "userId";
    private static final String BEARER = "Bearer ";

    private final AccessTokenVerifier verifier;

    public JwtAuthenticationFilter(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            verifier.verify(header.substring(BEARER.length()).trim()).ifPresent(user -> {
                var auth = new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role())));
                SecurityContextHolder.getContext().setAuthentication(auth);
                MDC.put(MDC_USER_ID, user.id().toString());
            });
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_USER_ID);
        }
    }
}
