package com.quantgen.admin.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.account.api.AccountDto;
import com.quantgen.admin.api.AdminDtos.AccountStatusRequest;
import com.quantgen.admin.api.AdminDtos.AdminUserDetailDto;
import com.quantgen.admin.api.AdminDtos.AdminUserDto;
import com.quantgen.admin.api.AdminDtos.StatsDto;
import com.quantgen.admin.service.AdminService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.audit.AuditService.AuditLogDto;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.7 users, account status, stats and audit log (role ADMIN, enforced in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin")
public class AdminController {

    private final AdminService admin;
    private final AuditService audit;

    public AdminController(AdminService admin, AuditService audit) {
        this.admin = admin;
        this.audit = audit;
    }

    @GetMapping("/users")
    @Operation(summary = "Users, newest first; q matches name, email or phone (case-insensitive)")
    public PageDto<AdminUserDto> users(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return admin.users(q, PageDto.pageable(page, size));
    }

    @GetMapping("/users/{id}")
    public AdminUserDetailDto user(@PathVariable UUID id) {
        return admin.user(id);
    }

    @PatchMapping("/accounts/{id}/status")
    @Operation(summary = "Freeze or unfreeze an account (reason required, audited, customer notified)")
    public AccountDto changeStatus(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                   @Valid @RequestBody AccountStatusRequest request) {
        return admin.changeAccountStatus(user.id(), id, request.status(), request.reason());
    }

    @GetMapping("/stats")
    public StatsDto stats() {
        return admin.stats();
    }

    @GetMapping("/audit-logs")
    @Operation(summary = "Audit trail, newest first; actor = email substring, action = exact")
    public PageDto<AuditLogDto> auditLogs(@RequestParam(required = false) String actor,
                                          @RequestParam(required = false) String action,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer size) {
        return audit.search(actor, action, PageDto.pageable(page, size));
    }
}
