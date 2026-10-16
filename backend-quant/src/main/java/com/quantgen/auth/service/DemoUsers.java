package com.quantgen.auth.service;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.api.AuthDtos.RegisterRequest;
import com.quantgen.auth.domain.Role;
import com.quantgen.auth.domain.User;
import com.quantgen.auth.domain.UserRepository;

/**
 * Creates the demo users for the {@code dev} and {@code demo} profiles (PRD §10). Only the demo seeder
 * calls this. Idempotent: an existing user is kept, but its password is reset to the configured one so
 * an operator can rotate it by changing the env var and restarting.
 */
@Service
public class DemoUsers {

    private final UserRepository users;
    private final AuthService authService;
    private final PasswordEncoder passwordEncoder;

    public DemoUsers(UserRepository users, AuthService authService, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.authService = authService;
        this.passwordEncoder = passwordEncoder;
    }

    /** An ADMIN user (no bank account; admins operate the bank). Returns its id. */
    @Transactional
    public UUID ensureAdmin(String fullName, String email, String phone, String password) {
        User user = users.findByEmail(email).orElseGet(() ->
                users.save(new User(fullName, email, phone, passwordEncoder.encode(password), Role.ADMIN)));
        resetPasswordIfChanged(user, password);
        return user.getId();
    }

    /** A CUSTOMER registered through the normal flow (so it gets its SAVINGS account). Returns its id. */
    @Transactional
    public UUID ensureCustomer(String fullName, String email, String phone, String password) {
        if (!users.existsByEmail(email)) {
            authService.register(new RegisterRequest(fullName, email, phone, password));
        }
        User user = users.findByEmail(email).orElseThrow();
        resetPasswordIfChanged(user, password);
        return user.getId();
    }

    private void resetPasswordIfChanged(User user, String password) {
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.changePasswordHash(passwordEncoder.encode(password));
            users.save(user);
        }
    }
}
