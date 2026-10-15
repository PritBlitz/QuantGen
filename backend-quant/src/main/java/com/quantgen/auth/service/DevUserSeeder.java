package com.quantgen.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.api.AuthDtos.RegisterRequest;
import com.quantgen.auth.domain.Role;
import com.quantgen.auth.domain.User;
import com.quantgen.auth.domain.UserRepository;

/**
 * Demo users for local development only (PRD §10). Idempotent: existing users are left untouched.
 * Never active outside the {@code dev} profile.
 */
@Component
@Profile("dev")
@Order(10)
class DevUserSeeder implements ApplicationRunner {

    static final String ADMIN_EMAIL = "admin@quantgen.dev";
    static final String DEMO_EMAIL = "demo@quantgen.dev";

    private static final Logger log = LoggerFactory.getLogger(DevUserSeeder.class);

    private final UserRepository users;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    DevUserSeeder(UserRepository users, AuthService authService, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!users.existsByEmail(ADMIN_EMAIL)) {
            users.save(new User("QuantGen Admin", ADMIN_EMAIL, "+919800000001",
                    passwordEncoder.encode("Admin@1234"), Role.ADMIN));
            log.info("Seeded dev admin user {}", ADMIN_EMAIL);
        }
        if (!users.existsByEmail(DEMO_EMAIL)) {
            authService.register(new RegisterRequest("Demo Customer", DEMO_EMAIL, "+919800000002", "Demo@1234"));
            log.info("Seeded dev demo user {}", DEMO_EMAIL);
        }
    }
}
