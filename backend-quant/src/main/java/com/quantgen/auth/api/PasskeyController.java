package com.quantgen.auth.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.api.AuthDtos.AuthResponse;
import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.auth.security.passkey.PasskeyService;
import com.quantgen.auth.security.passkey.PasskeyService.PasskeyDto;
import com.quantgen.auth.service.AuthService;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.ClientInfo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * PRD 12.3 passkeys. {@code /login/**} is public (SecurityConfig); everything else needs the bearer token. The
 * registration and sign-in options are WebAuthn JSON (base64url binary fields), ready for
 * {@code PublicKeyCredential.parse*OptionsFromJSON}.
 */
@RestController
@RequestMapping("/api/v1/auth/passkeys")
@Tag(name = "Security")
public class PasskeyController {

    public record RegisterRequest(@NotNull JsonNode credential, @Size(max = 40) String name) {
    }

    public record LoginOptionsRequest(@Size(max = 254) String email) {
    }

    public record LoginRequest(@NotNull JsonNode credential) {
    }

    private final PasskeyService passkeys;
    private final StepUpService stepUp;
    private final AuthService authService;
    private final SessionResponses responses;

    public PasskeyController(PasskeyService passkeys, StepUpService stepUp, AuthService authService,
                             SessionResponses responses) {
        this.passkeys = passkeys;
        this.stepUp = stepUp;
        this.authService = authService;
        this.responses = responses;
    }

    @PostMapping("/register/options")
    public JsonNode registerOptions(@AuthenticationPrincipal AuthUser user) {
        return passkeys.registrationOptions(user.id());
    }

    @PostMapping("/register")
    public ResponseEntity<PasskeyDto> register(@AuthenticationPrincipal AuthUser user,
                                               @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(passkeys.register(user.id(), request.credential(), request.name()));
    }

    @GetMapping
    public List<PasskeyDto> list(@AuthenticationPrincipal AuthUser user) {
        return passkeys.list(user.id());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a passkey. Needs step-up SECURITY_SETTINGS.")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                       @RequestHeader(value = StepUpService.HEADER, required = false) String token) {
        stepUp.require(user.id(), StepUpAction.SECURITY_SETTINGS, token);
        passkeys.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login/options")
    @Operation(summary = "Start a passkey sign-in. The email is accepted but ignored, so the answer never "
            + "reveals who has a passkey.")
    public JsonNode loginOptions(@RequestBody(required = false) LoginOptionsRequest ignored) {
        return passkeys.loginOptions();
    }

    @PostMapping("/login")
    @Operation(summary = "Finish a passkey sign-in. Same response as /auth/login.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        UUID userId = passkeys.login(request.credential());
        AuthService.Session session = authService.loginWithPasskey(userId, ClientInfo.of(http));
        return responses.of(session);
    }
}
