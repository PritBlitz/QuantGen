package com.quantgen.risk;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.common.web.TextRules;
import com.quantgen.risk.FraudCaseService.FraudCaseDetailDto;
import com.quantgen.risk.FraudCaseService.FraudCaseDto;
import com.quantgen.risk.LoanDocumentService.LoanDocumentDto;
import com.quantgen.risk.LoanDocumentService.UnderwritingDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.8 risk and back office (V2-M6, rules-based core). /api/v1/admin/** is role ADMIN (SecurityConfig). */
@RestController
@Tag(name = "Risk")
public class RiskController {

    public record DecisionRequest(@NotBlank @Size(min = 5, max = 500)
                                  @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = "has invalid characters")
                                  String reason) {
    }

    private final FraudCaseService cases;
    private final LoanDocumentService documents;
    private final IdempotencyService idempotency;

    public RiskController(FraudCaseService cases, LoanDocumentService documents, IdempotencyService idempotency) {
        this.cases = cases;
        this.documents = documents;
        this.idempotency = idempotency;
    }

    @GetMapping("/api/v1/admin/fraud-cases")
    @Operation(summary = "Fraud review queue, newest first; status = OPEN, RELEASED or REJECTED")
    public PageDto<FraudCaseDto> list(@RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return cases.list(status, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)));
    }

    @GetMapping("/api/v1/admin/fraud-cases/{id}")
    @Operation(summary = "One case with its signals, the customer's baseline and the investigator memo (once written)")
    public FraudCaseDetailDto get(@PathVariable UUID id) {
        return cases.get(id);
    }

    @PostMapping("/api/v1/admin/fraud-cases/{id}/release")
    @Operation(summary = "Release: posts the held transfer. Requires Idempotency-Key and a reason.")
    public ResponseEntity<FraudCaseDto> release(@AuthenticationPrincipal AuthUser admin, @PathVariable UUID id,
                                                @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                @Valid @RequestBody DecisionRequest body, HttpServletRequest http) {
        return Idempotent.ok(cases.release(admin.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), body), id, body.reason().strip()));
    }

    @PostMapping("/api/v1/admin/fraud-cases/{id}/reject")
    @Operation(summary = "Reject: the transfer never happens and the hold is released. Requires Idempotency-Key and a reason.")
    public ResponseEntity<FraudCaseDto> reject(@AuthenticationPrincipal AuthUser admin, @PathVariable UUID id,
                                               @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                               @Valid @RequestBody DecisionRequest body, HttpServletRequest http) {
        return Idempotent.ok(cases.reject(admin.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), body), id, body.reason().strip()));
    }

    @PostMapping(value = "/api/v1/loans/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a PDF, PNG or JPEG (≤ 5 MB, at most 3) to your pending loan application; consent=true required")
    public LoanDocumentDto upload(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                  @RequestPart(value = "file", required = false) MultipartFile file,
                                  @RequestParam(defaultValue = "false") boolean consent) {
        return documents.upload(user.id(), id, file, consent);
    }

    @GetMapping("/api/v1/loans/{id}/documents")
    @Operation(summary = "Documents attached to a loan (the owner, or an admin). Metadata only; files are never served.")
    public List<LoanDocumentDto> documents(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return documents.listFor(user.id(), user.isAdmin(), id);
    }

    @GetMapping("/api/v1/admin/loans/{id}/underwriting")
    @Operation(summary = "The Underwriting Analyst's memo; PENDING until the agent (V2-M4) has written one")
    public UnderwritingDto underwriting(@PathVariable UUID id) {
        return documents.underwriting(id);
    }
}
