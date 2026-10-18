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
import com.quantgen.common.error.ApiException;
import com.quantgen.common.security.AuthUser;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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

    public record OptionsResponse(List<String> methods) {
    }

    public record VerifyRequest(@NotNull StepUpAction action, @Size(max = 6) String pin) {

        @Override
        public String toString() {
            return "VerifyRequest[action=" + action + "]"; // never print the PIN
        }
    }

    private final StepUpService stepUp;

    public StepUpController(StepUpService stepUp) {
        this.stepUp = stepUp;
    }

    @PostMapping("/options")
    public OptionsResponse options(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody OptionsRequest request) {
        return new OptionsResponse(stepUp.methods(user.id()));
    }

    @PostMapping("/verify")
    public StepUpService.IssuedToken verify(@AuthenticationPrincipal AuthUser user,
                                            @Valid @RequestBody VerifyRequest request) {
        if (request.pin() == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pin", "is required");
        }
        return stepUp.verifyPin(user.id(), request.action(), request.pin());
    }
}
