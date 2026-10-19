package com.quantgen.auth.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.PersonalAccessTokenService;
import com.quantgen.auth.security.PersonalAccessTokenService.CreatedTokenDto;
import com.quantgen.auth.security.PersonalAccessTokenService.TokenDto;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.TextRules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.12 developer access (V2-M10): personal access tokens for the MCP server and other clients. */
@RestController
@Tag(name = "Developer access")
public class TokenController {

    public record CreateTokenRequest(
            @NotBlank @Size(max = 40) @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = "has invalid characters") String name,
            @NotBlank String scope,
            @NotNull Integer expiresInDays) {
    }

    private final PersonalAccessTokenService tokens;

    public TokenController(PersonalAccessTokenService tokens) {
        this.tokens = tokens;
    }

    @PostMapping("/api/v1/users/me/tokens")
    @Operation(summary = "Create a token (shown once). Needs X-Step-Up-Token for TOKEN_CREATE; at most 10 active, ≤ 90 days.")
    public ResponseEntity<CreatedTokenDto> create(@AuthenticationPrincipal AuthUser user,
                                                  @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                                                  @Valid @RequestBody CreateTokenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(tokens.create(user, request.name().strip(), request.scope(), request.expiresInDays(), stepUpToken));
    }

    @GetMapping("/api/v1/users/me/tokens")
    @Operation(summary = "Your unrevoked tokens (never the token value)")
    public List<TokenDto> list(@AuthenticationPrincipal AuthUser user) {
        return tokens.list(user.id());
    }

    @DeleteMapping("/api/v1/users/me/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Revoke a token; it stops working immediately")
    public void revoke(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        tokens.revoke(user.id(), id);
    }
}
