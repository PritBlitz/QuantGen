package com.quantgen.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
class AuthConfig {

    /** BCrypt, cost 12 (ARCHITECTURE §5). Only the test profile lowers it, to keep test suites fast. */
    @Bean
    PasswordEncoder passwordEncoder(@Value("${qg.auth.bcrypt-cost:12}") int cost) {
        return new BCryptPasswordEncoder(cost);
    }
}
