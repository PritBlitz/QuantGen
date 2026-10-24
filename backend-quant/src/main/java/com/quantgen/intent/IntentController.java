package com.quantgen.intent;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.security.AgentAuthenticationFilter;
import com.quantgen.common.security.PatAuthenticationFilter;
import com.quantgen.common.web.TextRules;
import com.quantgen.intent.IntentService.IntentDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;

/** PRD 12.6 intents (V2-M4 core side). Proposals come from agents or a read+propose token; the user decides here. */
@RestController
@Tag(name = "Intents")
public class IntentController {

    public record ProposeRequest(@NotBlank String kind, @NotNull JsonNode payload, UUID conversationId,
                                 @NotBlank @Size(max = 1000) String rationale,
                                 String agent) {
    }

    private final IntentService intents;
    private final IdempotencyService idempotency;

    public IntentController(IntentService intents, IdempotencyService idempotency) {
        this.intents = intents;
        this.idempotency = idempotency;
    }

    @PostMapping("/api/v1/intents")
    @Operation(summary = "Propose an action for the user to approve. Validated and rendered by the server; expires in 15 minutes.")
    public ResponseEntity<IntentDto> propose(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody ProposeRequest body,
                                             HttpServletRequest http) {
        UUID tokenId = (UUID) http.getAttribute(PatAuthenticationFilter.TOKEN_ID_ATTRIBUTE);
        UUID agentConversation = (UUID) http.getAttribute(AgentAuthenticationFilter.CONVERSATION_ATTRIBUTE);
        // A token-proposed intent is always labelled as such; the agent name can't be chosen by the client.
        String agent = tokenId != null ? "MCP_CLIENT" : body.agent() == null ? "CONCIERGE" : body.agent();
        // An agent token proposes only into the conversation it was minted for.
        UUID conversationId = agentConversation != null ? agentConversation : body.conversationId();
        return ResponseEntity.status(HttpStatus.CREATED).body(intents.propose(user.id(), tokenId, agent, body.kind(),
                body.payload(), conversationId, rationale(body.rationale())));
    }

    /** Model text: line breaks become spaces; any other control character is refused (log-forging, D-023). */
    private static String rationale(String raw) {
        String flat = raw.replaceAll("[\r\n\t]+", " ").strip();
        if (!flat.matches(TextRules.NO_CONTROL_CHARS)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "rationale", TextRules.NO_CONTROL_CHARS_MESSAGE);
        }
        return flat;
    }

    @GetMapping("/api/v1/intents")
    @Operation(summary = "Your intents, newest first (at most 100); status = PROPOSED, EXECUTED, REJECTED, EXPIRED or FAILED")
    public List<IntentDto> list(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String status) {
        return intents.list(user.id(), status);
    }

    @PostMapping("/api/v1/intents/{id}/approve")
    @Operation(summary = "Approve: re-validates and executes. Requires Idempotency-Key, and X-Step-Up-Token (INTENT_APPROVE) "
            + "when requiresStepUp. A business refusal answers 200 with status FAILED and failureCode.")
    public ResponseEntity<IntentDto> approve(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                             @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                             @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                                             HttpServletRequest http) {
        return Idempotent.ok(intents.approve(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), id), id, stepUpToken));
    }

    @PostMapping("/api/v1/intents/{id}/reject")
    @Operation(summary = "Reject an open intent; nothing happens")
    public IntentDto reject(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return intents.reject(user.id(), id);
    }
}
