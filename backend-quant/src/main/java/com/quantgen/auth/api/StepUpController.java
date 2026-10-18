package com.quantgen.auth.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.auth.security.passkey.PasskeyService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.security.AuthUser;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

/**
 * PRD 12.3 step-up. These paths sit under /auth but require a signed-in user (SecurityConfig). A wrong PIN is
 * 400, never 401, so a client that treats 401 as "session expired" does not loop.
 */
@RestController
@RequestMapping("/api/v1/auth/step-up")
@Tag(name = "Security")
public class StepUpController {

    public record OptionsRequest(@NotNull StepUpAction action) {
    }

    /** {@code passkeyOptions} is present only when the user has a passkey (WebAuthn request options JSON). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OptionsResponse(List<String> methods, JsonNode passkeyOptions) {
    }

    public record VerifyRequest(@NotNull StepUpAction action, @Size(max = 6) String pin, JsonNode credential) {

        @Override
        public String toString() {
            return "VerifyRequest[action=" + action + "]"; // never print the PIN
        }
    }

    private final StepUpService stepUp;
    private final PasskeyService passkeys;

    public StepUpController(StepUpService stepUp, PasskeyService passkeys) {
        this.stepUp = stepUp;
        this.passkeys = passkeys;
    }

    @PostMapping("/options")
    public OptionsResponse options(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody OptionsRequest request) {
        List<String> methods = stepUp.methods(user.id());
        JsonNode passkeyOptions = methods.contains("PASSKEY")
                ? passkeys.stepUpOptions(user.id(), request.action().name()) : null;
        return new OptionsResponse(methods, passkeyOptions);
    }

    @PostMapping("/verify")
    public StepUpService.IssuedToken verify(@AuthenticationPrincipal AuthUser user,
                                            @Valid @RequestBody VerifyRequest request) {
        if (request.credential() != null && !request.credential().isNull()) {
            passkeys.verifyStepUp(user.id(), request.action().name(), request.credential());
            return stepUp.issue(user.id(), request.action(), "PASSKEY");
        }
        if (request.pin() == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pin", "is required");
        }
        return stepUp.verifyPin(user.id(), request.action(), request.pin());
    }
}
