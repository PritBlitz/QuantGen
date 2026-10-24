package com.quantgen.autopilot;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpService;
import com.quantgen.autopilot.AutopilotService.RuleDto;
import com.quantgen.autopilot.AutopilotService.RunDto;
import com.quantgen.autopilot.AutopilotService.TemplateDto;
import com.quantgen.autopilot.AutopilotService.ValidateDto;
import com.quantgen.autopilot.RuleSimulator.SimulationDto;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.flags.FeatureFlagService;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.copilot.CopilotService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import tools.jackson.databind.JsonNode;

/** PRD 12.7 Autopilot (V2-M5 core). Writes that activate a rule need X-Step-Up-Token for RULE_ACTIVATE. */
@RestController
@Tag(name = "Autopilot")
public class AutopilotController {

    public record CompileRequest(String text) {
    }

    public record RuleRequest(JsonNode rule) {
    }

    public record SimulateRequest(JsonNode rule, Integer days) {
    }

    public record SaveRequest(String name, JsonNode rule) {
    }

    private final AutopilotService autopilot;
    private final RuleSimulator simulator;
    private final FeatureFlagService flags;
    private final CopilotService copilot;

    public AutopilotController(AutopilotService autopilot, RuleSimulator simulator, FeatureFlagService flags,
                               CopilotService copilot) {
        this.autopilot = autopilot;
        this.simulator = simulator;
        this.flags = flags;
        this.copilot = copilot;
    }

    public record CompileDto(RuleDsl rule, String rendering, List<String> warnings) {
    }

    @PostMapping("/api/v1/autopilot/compile")
    @Operation(summary = "Plain language → DSL via the Planner agent (needs the ai flag and agent-quant). The draft is "
            + "validated like any rule (422 RULE_INVALID) and rendered by the server.")
    public CompileDto compile(@AuthenticationPrincipal AuthUser user, @RequestBody CompileRequest request) {
        autopilot.requireEnabled();
        if (!flags.isEnabled("ai")) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "The AI layer is switched off; build the rule with the form");
        }
        AutopilotService.requireText(request.text());
        CopilotService.Draft draft = copilot.compile(user.id(), request.text().strip());
        // The model's rule is a proposal: validated against the user's own data and rendered by the server (D-024).
        RuleDsl rule = autopilot.requireValid(user.id(), draft.rule());
        return new CompileDto(rule, autopilot.render(rule), draft.warnings());
    }

    @PostMapping("/api/v1/autopilot/validate")
    @Operation(summary = "Validate a rule and get the server's plain-language rendering")
    public ValidateDto validate(@AuthenticationPrincipal AuthUser user, @RequestBody RuleRequest request) {
        return autopilot.validate(user.id(), request.rule());
    }

    @PostMapping("/api/v1/autopilot/simulate")
    @Operation(summary = "Replay a rule against your last 1–180 days (default 90) of real activity; nothing moves")
    public SimulationDto simulate(@AuthenticationPrincipal AuthUser user, @RequestBody SimulateRequest request) {
        autopilot.requireEnabled();
        RuleDsl rule = autopilot.requireValid(user.id(), request.rule());
        return simulator.simulate(user.id(), rule, request.days() == null ? 90 : request.days());
    }

    @PostMapping("/api/v1/autopilot/rules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Activate a rule (status ACTIVE). Needs X-Step-Up-Token for RULE_ACTIVATE; at most 10 active.")
    public RuleDto create(@AuthenticationPrincipal AuthUser user,
                          @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                          @RequestBody SaveRequest request) {
        return autopilot.create(user.id(), request.name(), request.rule(), stepUpToken);
    }

    @GetMapping("/api/v1/autopilot/rules")
    @Operation(summary = "Your rules")
    public List<RuleDto> list(@AuthenticationPrincipal AuthUser user) {
        return autopilot.list(user.id());
    }

    @GetMapping("/api/v1/autopilot/rules/{id}")
    @Operation(summary = "One rule")
    public RuleDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return autopilot.get(user.id(), id);
    }

    @PutMapping("/api/v1/autopilot/rules/{id}")
    @Operation(summary = "Edit a rule: a new version. Needs X-Step-Up-Token for RULE_ACTIVATE.")
    public RuleDto update(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                          @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                          @RequestBody SaveRequest request) {
        return autopilot.update(user.id(), id, request.name(), request.rule(), stepUpToken);
    }

    @PostMapping("/api/v1/autopilot/rules/{id}/pause")
    @Operation(summary = "Pause a rule (always allowed, even with Autopilot switched off)")
    public RuleDto pause(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return autopilot.pause(user.id(), id);
    }

    @PostMapping("/api/v1/autopilot/rules/{id}/resume")
    @Operation(summary = "Resume a paused rule. Needs X-Step-Up-Token for RULE_ACTIVATE.")
    public RuleDto resume(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                          @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken) {
        return autopilot.resume(user.id(), id, stepUpToken);
    }

    @DeleteMapping("/api/v1/autopilot/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a rule (its run history is kept)")
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        autopilot.delete(user.id(), id);
    }

    @GetMapping("/api/v1/autopilot/rules/{id}/runs")
    @Operation(summary = "Run history of a rule, newest first")
    public PageDto<RunDto> runs(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return autopilot.runs(user.id(), id, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
    }

    @GetMapping("/api/v1/autopilot/templates")
    @Operation(summary = "Starting points; each has a prompt for the Planner (rule is null: accounts are yours to pick)")
    public List<TemplateDto> templates() {
        return AutopilotService.TEMPLATES;
    }
}
