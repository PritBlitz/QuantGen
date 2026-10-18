package com.quantgen.common.security;

import java.util.List;
import java.util.Set;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

/**
 * Authenticates STOMP sessions (ARCHITECTURE §6): the CONNECT frame must carry
 * {@code Authorization: Bearer <accessToken>}. Clients may only subscribe to whitelisted topics and
 * may not send to the server at all in v1.
 */
@Component
public class StompAuthInterceptor implements ChannelInterceptor {

    static final Set<String> SUBSCRIBABLE = Set.of("/topic/prices", "/user/queue/notifications");

    private final AccessTokenVerifier verifier;

    public StompAuthInterceptor(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> {
                AuthUser user = authenticate(accessor.getFirstNativeHeader("Authorization"));
                // The principal name is the user id: that is how /user/queue/** destinations are routed, so a
                // user can only ever receive their own messages.
                accessor.setUser(new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))) {
                    @Override
                    public String getName() {
                        return user.id().toString();
                    }
                });
            }
            case SUBSCRIBE -> {
                requireUser(accessor);
                if (!SUBSCRIBABLE.contains(accessor.getDestination())) {
                    throw new MessageDeliveryException("Unknown or forbidden destination");
                }
            }
            case SEND -> throw new MessageDeliveryException("Sending is not supported");
            default -> {
                // DISCONNECT, UNSUBSCRIBE, ACK, NACK, heartbeats
            }
        }
        return message;
    }

    private AuthUser authenticate(String header) {
        if (header == null) {
            throw new MessageDeliveryException("Missing Authorization header");
        }
        String token = header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : header.trim();
        return verifier.verify(token).orElseThrow(() -> new MessageDeliveryException("Invalid or expired token"));
    }

    private static void requireUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessageDeliveryException("Not authenticated");
        }
    }
}
