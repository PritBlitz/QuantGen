package com.quantgen.common.config;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import com.quantgen.common.security.StompAuthInterceptor;

import tools.jackson.databind.json.JsonMapper;

/**
 * STOMP over {@code /ws} (PRD §7.5): a plain WebSocket endpoint at {@code /ws} and a SockJS fallback under
 * {@code /ws/**}. A simple in-memory broker serves {@code /topic/prices}. Payloads use the app's
 * JsonMapper, so money stays a JSON string.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final CorsProperties cors;
    private final StompAuthInterceptor authInterceptor;
    private final JsonMapper jsonMapper;

    public WebSocketConfig(CorsProperties cors, StompAuthInterceptor authInterceptor, JsonMapper jsonMapper) {
        this.cors = cors;
        this.authInterceptor = authInterceptor;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = cors.allowedOrigins().toArray(String[]::new);
        registry.addEndpoint("/ws").setAllowedOrigins(origins);
        registry.addEndpoint("/ws").setAllowedOrigins(origins).withSockJS();
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> converters) {
        converters.add(new JacksonJsonMessageConverter(jsonMapper));
        return false; // don't add the defaults (they would use a differently configured mapper)
    }
}
