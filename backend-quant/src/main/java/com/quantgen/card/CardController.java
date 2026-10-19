package com.quantgen.card;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpService;
import com.quantgen.card.CardService.CardDto;
import com.quantgen.card.CardService.RevealDto;
import com.quantgen.card.SandboxService.AuthorizationDto;
import com.quantgen.card.SandboxService.AuthorizeRequest;
import com.quantgen.card.SandboxService.SettlementDto;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.TextRules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.11 virtual cards and the merchant sandbox (V2-M9). */
@RestController
@Tag(name = "Cards")
public class CardController {

    public record ControlsRequest(Boolean frozen, Boolean online,
                                  @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal perTxnLimit,
                                  @Size(max = 8) List<@NotNull MerchantCategory> blockedCategories) {
    }

    public record AuthorizeBody(
            @NotBlank @Pattern(regexp = "^[0-9 ]{12,23}$", message = "must be a card number") String cardNumber,
            @NotBlank @Pattern(regexp = "^(0[1-9]|1[0-2])/[0-9]{2}$", message = "must be MM/YY") String expiry,
            @NotBlank @Pattern(regexp = "^[0-9]{3}$", message = "must be 3 digits") String cvv,
            @NotNull @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal amount,
            @NotBlank @Size(max = 40) @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = "has invalid characters") String merchant,
            @NotNull MerchantCategory category) {
    }

    public record SettleBody(@MoneyAmount(min = "0.01", max = "200000.00") BigDecimal amount) {
    }

    private final CardService cards;
    private final SandboxService sandbox;
    private final IdempotencyService idempotency;

    public CardController(CardService cards, SandboxService sandbox, IdempotencyService idempotency) {
        this.cards = cards;
        this.sandbox = sandbox;
        this.idempotency = idempotency;
    }

    @PostMapping("/api/v1/accounts/{id}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Issue the virtual debit card of a savings or checking account (one per account)")
    public CardDto create(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return cards.create(user.id(), id);
    }

    @GetMapping("/api/v1/cards")
    @Operation(summary = "Your cards (last 4 digits only)")
    public List<CardDto> list(@AuthenticationPrincipal AuthUser user) {
        return cards.list(user.id());
    }

    @PostMapping("/api/v1/cards/{id}/reveal")
    @Operation(summary = "Full number and the current 30-second CVV. Needs X-Step-Up-Token for CARD_REVEAL.")
    public ResponseEntity<RevealDto> reveal(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                            @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cards.reveal(user.id(), id, stepUpToken));
    }

    @PatchMapping("/api/v1/cards/{id}/controls")
    @Operation(summary = "Freeze/unfreeze, online on/off, per-transaction limit (₹1–₹2,00,000), blocked merchant categories")
    public CardDto controls(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                            @Valid @RequestBody ControlsRequest request) {
        return cards.updateControls(user.id(), id, request.frozen(), request.online(), request.perTxnLimit(),
                request.blockedCategories());
    }

    @PostMapping("/api/v1/sandbox/merchant/authorize")
    @Operation(summary = "Sandbox checkout: approve (placing a 7-day hold) or decline with a reason. Requires Idempotency-Key.")
    public ResponseEntity<AuthorizationDto> authorize(@AuthenticationPrincipal AuthUser user,
                                                      @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                      @Valid @RequestBody AuthorizeBody body, HttpServletRequest http) {
        AuthorizeRequest req = new AuthorizeRequest(body.cardNumber(), body.expiry(), body.cvv(), body.amount(),
                body.merchant().strip(), body.category());
        return Idempotent.ok(sandbox.authorize(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), body), req));
    }

    @PostMapping("/api/v1/sandbox/merchant/{authId}/capture")
    @Operation(summary = "Capture an approved authorization (all of it by default, or less). Requires Idempotency-Key.")
    public ResponseEntity<SettlementDto> capture(@AuthenticationPrincipal AuthUser user, @PathVariable UUID authId,
                                                 @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                 @Valid @RequestBody(required = false) SettleBody body,
                                                 HttpServletRequest http) {
        SettleBody b = body == null ? new SettleBody(null) : body;
        return Idempotent.ok(sandbox.capture(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), b), authId, b.amount()));
    }

    @PostMapping("/api/v1/sandbox/merchant/{authId}/refund")
    @Operation(summary = "Refund a captured payment (what's left by default, or part of it). Requires Idempotency-Key.")
    public ResponseEntity<SettlementDto> refund(@AuthenticationPrincipal AuthUser user, @PathVariable UUID authId,
                                                @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                @Valid @RequestBody(required = false) SettleBody body,
                                                HttpServletRequest http) {
        SettleBody b = body == null ? new SettleBody(null) : body;
        return Idempotent.ok(sandbox.refund(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), b), authId, b.amount()));
    }
}
