package com.quantgen.auth.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.KycService;
import com.quantgen.auth.security.KycService.KycDto;
import com.quantgen.auth.security.PinSetupService;
import com.quantgen.auth.security.SessionService;
import com.quantgen.auth.security.SessionService.SessionDto;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.security.AuthUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PRD 12.3 (without limits, which live in the payment module): PIN, sessions and KYC. */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Security")
public class SecurityController {

    public record SetPinRequest(@NotBlank @Size(max = 6) String pin, @Size(max = 200) String password) {

        @Override
        public String toString() {
            return "SetPinRequest[redacted]";
        }
    }

    public record KycRequest(@NotBlank @Size(max = 20) String pan, java.time.LocalDate dateOfBirth,
                             @NotBlank @Size(max = 300) String address) {

        @Override
        public String toString() {
            return "KycRequest[redacted]";
        }
    }

    private final PinSetupService pinSetup;
    private final SessionService sessions;
    private final KycService kyc;

    public SecurityController(PinSetupService pinSetup, SessionService sessions, KycService kyc) {
        this.pinSetup = pinSetup;
        this.sessions = sessions;
        this.kyc = kyc;
    }

    @PutMapping("/pin")
    @Operation(summary = "Set or change the 6-digit transaction PIN. Needs step-up SECURITY_SETTINGS; "
            + "the first PIN needs the account password instead.")
    public ResponseEntity<Void> setPin(@AuthenticationPrincipal AuthUser user,
                                       @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                                       @Valid @RequestBody SetPinRequest request) {
        pinSetup.setPin(user.id(), stepUpToken, request.pin(), request.password());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<SessionDto> sessions(@AuthenticationPrincipal AuthUser user) {
        return sessions.list(user.id(), user.sessionId());
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        sessions.revoke(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/sessions")
    @Operation(summary = "Sign out every other device (others=true is required)")
    public ResponseEntity<Void> revokeOthers(@AuthenticationPrincipal AuthUser user,
                                             @RequestParam(defaultValue = "false") boolean others) {
        if (!others) {
            throw ApiException.fieldError(org.springframework.http.HttpStatus.BAD_REQUEST, "others", "must be true");
        }
        sessions.revokeOthers(user.id(), user.sessionId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/kyc")
    public KycDto getKyc(@AuthenticationPrincipal AuthUser user) {
        return kyc.get(user.id());
    }

    @PutMapping("/kyc")
    @Operation(summary = "Submit KYC details. The PAN is returned masked; replacing details on file needs step-up.")
    public KycDto putKyc(@AuthenticationPrincipal AuthUser user,
                         @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                         @Valid @RequestBody KycRequest request) {
        LocalDate dob = request.dateOfBirth();
        return kyc.submit(user.id(), stepUpToken, request.pan(), dob, request.address());
    }
}
