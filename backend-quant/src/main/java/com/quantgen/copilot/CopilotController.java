package com.quantgen.copilot;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.quantgen.common.security.AuthUser;
import com.quantgen.copilot.AiBudget.UsageDto;
import com.quantgen.copilot.CopilotService.ConversationDetailDto;
import com.quantgen.copilot.CopilotService.ConversationDto;
import com.quantgen.copilot.CopilotService.TraceDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.6 copilot (V2-M4) and the admin AI views of 12.8. The browser only ever talks to the core. */
@RestController
@Tag(name = "Copilot")
public class CopilotController {

    public record MessageRequest(String text, CopilotService.Context context) {
    }

    private final CopilotService copilot;
    private final AiBudget budget;

    public CopilotController(CopilotService copilot, AiBudget budget) {
        this.copilot = copilot;
        this.budget = budget;
    }

    @PostMapping("/api/v1/copilot/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a conversation (needs the ai flag)")
    public ConversationDto create(@AuthenticationPrincipal AuthUser user) {
        return copilot.create(user.id());
    }

    @GetMapping("/api/v1/copilot/conversations")
    @Operation(summary = "Your conversations, most recent first (kept 30 days)")
    public List<ConversationDto> list(@AuthenticationPrincipal AuthUser user) {
        return copilot.list(user.id());
    }

    @GetMapping("/api/v1/copilot/conversations/{id}")
    @Operation(summary = "A conversation with its messages, intents and citations")
    public ConversationDetailDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return copilot.get(user.id(), id);
    }

    @DeleteMapping("/api/v1/copilot/conversations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a conversation, its messages and traces")
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        copilot.delete(user.id(), id);
    }

    @PostMapping(value = "/api/v1/copilot/conversations/{id}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Send a message; the answer streams as SSE events token, tool, intent, citation, done, error")
    public ResponseEntity<SseEmitter> message(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                              @RequestBody MessageRequest request) {
        SseEmitter emitter = copilot.turn(user.id(), id, request == null ? null : request.text(),
                request == null ? null : request.context());
        // No caching anywhere, and tell nginx not to buffer the stream.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    @GetMapping("/api/v1/copilot/runs/{runId}/trace")
    @Operation(summary = "Why did the assistant say this? The steps of one of your runs (details redacted)")
    public TraceDto trace(@AuthenticationPrincipal AuthUser user, @PathVariable UUID runId) {
        return copilot.trace(user.id(), runId);
    }

    @GetMapping("/api/v1/admin/ai/usage")
    @Operation(summary = "AI tokens, cost, runs per agent and per day, and the monthly budget")
    public UsageDto usage(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return budget.usage(from, to);
    }

    @GetMapping("/api/v1/admin/ai/runs/{runId}")
    @Operation(summary = "Full trace of any run")
    public TraceDto adminTrace(@PathVariable UUID runId) {
        return copilot.adminTrace(runId);
    }
}
