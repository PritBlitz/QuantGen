package com.quantgen.auth.api;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.quantgen.auth.domain.Role;

/** PRD §7.2 request and response bodies. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 2, max = 100) String fullName,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
            String phone,
            @NotNull @StrongPassword String password) {

        @Override
        public String toString() {
            return "RegisterRequest[email=" + email + "]"; // never print the password
        }
    }

    public record LoginRequest(
            @NotBlank @Size(max = 254) String email,
            @NotBlank @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "LoginRequest[email=" + email + "]";
        }
    }

    public record UserDto(UUID id, String fullName, String email, String phone, Role role, Instant createdAt) {
    }

    /** {@code expiresIn} is the access token lifetime in seconds. */
    public record AuthResponse(String accessToken, long expiresIn, UserDto user) {

        @Override
        public String toString() {
            return "AuthResponse[user=" + user.id() + "]";
        }
    }
}
