package com.quantgen.common.flags;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.flags.FeatureFlagService.FlagDto;
import com.quantgen.common.security.AuthUser;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** PRD sections 12.8 and 12.14: feature flags for the UI, and the admin toggle. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Feature flags")
public class FeatureFlagController {

    public record SetFlagRequest(@NotNull Boolean enabled) {
    }

    private final FeatureFlagService flags;

    public FeatureFlagController(FeatureFlagService flags) {
        this.flags = flags;
    }

    @GetMapping("/feature-flags")
    public Map<String, Boolean> flags() {
        return flags.snapshot();
    }

    @GetMapping("/admin/feature-flags")
    public List<FlagDto> adminList() {
        return flags.listAll();
    }

    @PutMapping("/admin/feature-flags/{name}")
    public FlagDto set(@AuthenticationPrincipal AuthUser admin, @PathVariable String name,
                       @Valid @RequestBody SetFlagRequest body) {
        return flags.set(admin.id(), name, body.enabled());
    }
}
