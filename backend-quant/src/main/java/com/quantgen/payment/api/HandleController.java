package com.quantgen.payment.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.security.AuthUser;
import com.quantgen.payment.service.HandleService;
import com.quantgen.payment.service.HandleService.HandleDto;
import com.quantgen.payment.service.HandleService.PayeeDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.4 handles (F-PAY-5) and confirmation of payee (F-PAY-6). */
@RestController
@Tag(name = "Payments")
public class HandleController {

    public record SetHandleRequest(@NotBlank @Size(max = 40) String handle, @NotNull UUID accountId) {
    }

    private final HandleService handles;

    public HandleController(HandleService handles) {
        this.handles = handles;
    }

    @PutMapping("/api/v1/users/me/handle")
    @Operation(summary = "Claim or change your handle (name@qg, 3-30 of [a-z0-9.]) and the account it pays into. "
            + "The handle itself can change once per 30 days; re-pointing it to another account is always allowed.")
    public HandleDto set(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody SetHandleRequest request) {
        return handles.set(user.id(), request.handle(), request.accountId());
    }

    @GetMapping("/api/v1/users/me/handle")
    @Operation(summary = "Your handle, or 404 if you have none yet")
    public HandleDto get(@AuthenticationPrincipal AuthUser user) {
        return handles.get(user.id()).orElseThrow(() -> ApiException.notFound("Handle"));
    }

    @GetMapping("/api/v1/payees/resolve")
    @Operation(summary = "Who a handle or account number belongs to, with the name masked. "
            + "Exactly one of handle / accountNumber. Limited to 30 lookups per minute per user.")
    public PayeeDto resolve(@AuthenticationPrincipal AuthUser user,
                            @RequestParam(required = false) String handle,
                            @RequestParam(required = false) String accountNumber) {
        return handles.resolve(user.id(), handle, accountNumber);
    }
}
