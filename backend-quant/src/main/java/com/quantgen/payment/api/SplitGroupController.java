package com.quantgen.payment.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.payment.service.PaymentRequestService.PaymentRequestDto;
import com.quantgen.payment.service.SplitGroupService;
import com.quantgen.payment.service.SplitGroupService.ExpenseDto;
import com.quantgen.payment.service.SplitGroupService.GroupDto;
import com.quantgen.payment.service.SplitGroupService.ShareInput;
import com.quantgen.payment.service.SplitGroupService.SplitType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.4 split groups (F-PAY-12). No money moves here; settling up creates payment requests. */
@RestController
@RequestMapping("/api/v1/split-groups")
@Tag(name = "Payments")
public class SplitGroupController {

    public record CreateGroupRequest(@NotBlank @Size(max = 40) String name,
                                     @NotEmpty @Size(max = 19) List<@NotBlank @Size(max = 40) String> memberHandles) {
    }

    public record Share(@NotBlank @Size(max = 40) String handle, @NotNull BigDecimal value) {
    }

    public record Split(@NotNull SplitType type, @Size(max = 20) List<@Valid @NotNull Share> shares) {
    }

    public record ExpenseRequest(@NotBlank @Size(max = 40) String paidByHandle,
                                 @NotNull @MoneyAmount(min = "1.00", max = "1000000.00") BigDecimal amount,
                                 @NotBlank @Size(max = 60) String description,
                                 @NotNull @Valid Split split) {
    }

    private final SplitGroupService groups;

    public SplitGroupController(SplitGroupService groups) {
        this.groups = groups;
    }

    @PostMapping
    @Operation(summary = "Create a group with yourself and 1-19 other handles (you need a handle)")
    public ResponseEntity<GroupDto> create(@AuthenticationPrincipal AuthUser user,
                                           @Valid @RequestBody CreateGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(groups.create(user.id(), request.name(), request.memberHandles()));
    }

    @GetMapping
    public List<GroupDto> list(@AuthenticationPrincipal AuthUser user) {
        return groups.list(user.id());
    }

    @GetMapping("/{id}")
    public GroupDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return groups.get(user.id(), id);
    }

    @PostMapping("/{id}/expenses")
    @Operation(summary = "Add an expense. EQUAL splits among all members; EXACT shares are rupees, PERCENT shares are "
            + "percentages. Leftover paisa go to the payer.")
    public ResponseEntity<ExpenseDto> addExpense(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                                 @Valid @RequestBody ExpenseRequest request) {
        List<ShareInput> shares = request.split().shares() == null ? List.of()
                : request.split().shares().stream().map(s -> new ShareInput(s.handle(), s.value())).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(groups.addExpense(user.id(), id, request.paidByHandle(),
                request.amount(), request.description(), request.split().type(), shares));
    }

    @PostMapping("/{id}/settle")
    @Operation(summary = "Ask everyone who owes the group for what they owe you (payment requests). "
            + "Open settle-up requests are taken into account, so calling it twice doesn't ask twice.")
    public List<PaymentRequestDto> settle(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return groups.settle(user.id(), id);
    }
}
