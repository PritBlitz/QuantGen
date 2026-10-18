package com.quantgen.account.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountService;
import com.quantgen.account.service.DepositService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.common.web.TextRules;
import com.quantgen.ledger.domain.JournalType;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.3 accounts. */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    private final AccountService accounts;
    private final DepositService deposits;
    private final IdempotencyService idempotency;

    public AccountController(AccountService accounts, DepositService deposits, IdempotencyService idempotency) {
        this.accounts = accounts;
        this.deposits = deposits;
        this.idempotency = idempotency;
    }

    /** v1 body; {@code type: GOAL} adds name, target, targetDate and emoji (PRD 12.5). */
    public record CreateAccountRequest(
            @NotNull AccountType type,
            @Size(max = 40) @Pattern(regexp = TextRules.NO_CONTROL_CHARS,
                    message = "must not contain control characters") String name,
            @MoneyAmount(min = "100.00", max = "100000000.00") BigDecimal target,
            LocalDate targetDate,
            @Size(max = 16) @Pattern(regexp = TextRules.NO_CONTROL_CHARS,
                    message = "must not contain control characters") String emoji) {
    }

    public record DepositRequest(@NotNull @MoneyAmount(min = "1.00", max = "100000.00") BigDecimal amount) {
    }

    @GetMapping
    public List<AccountDto> list(@AuthenticationPrincipal AuthUser user) {
        return accounts.listAccounts(user.id());
    }

    @PostMapping
    @Operation(summary = "Open an account: SAVINGS or CHECKING (max 3 per customer), or a GOAL (max 10; needs name "
            + "and target of at least 100.00). LIMIT_EXCEEDED beyond the caps.")
    public ResponseEntity<AccountDto> create(@AuthenticationPrincipal AuthUser user,
                                             @Valid @RequestBody CreateAccountRequest request) {
        if (request.type() != AccountType.GOAL) {
            return ResponseEntity.status(HttpStatus.CREATED).body(accounts.createAccount(user.id(), request.type()));
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "name", "is required for a goal");
        }
        if (request.target() == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "target", "is required for a goal");
        }
        if (request.targetDate() != null
                && request.targetDate().isBefore(LocalDate.now(TimeConfig.BUSINESS_ZONE))) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "targetDate", "must not be in the past");
        }
        String emoji = request.emoji() == null || request.emoji().isBlank() ? null : request.emoji().trim();
        return ResponseEntity.status(HttpStatus.CREATED).body(accounts.createGoal(user.id(),
                new AccountService.GoalSpec(name, request.target(), request.targetDate(), emoji)));
    }

    @GetMapping("/{id}")
    public AccountDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return accounts.getAccount(user.id(), id);
    }

    @PostMapping("/{id}/deposit")
    @Operation(summary = "Demo deposit (max 1,00,000 per deposit). Requires Idempotency-Key.")
    public ResponseEntity<TransactionDto> deposit(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                                  @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                  @Valid @RequestBody DepositRequest request,
                                                  HttpServletRequest http) {
        IdempotencyService.Result<TransactionDto> result = deposits.deposit(user.id(), id, idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request.amount());
        return Idempotent.created(result);
    }

    @GetMapping("/{id}/transactions")
    @Operation(summary = "Transaction history, newest first. from/to are IST dates (yyyy-MM-dd), inclusive.")
    public PageDto<TransactionDto> transactions(
            @AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) JournalType type) {
        return accounts.transactions(user.id(), id, from, to, type, PageDto.pageable(page, size));
    }

    @GetMapping("/{id}/statement")
    @Operation(summary = "CSV statement. from/to are IST dates (yyyy-MM-dd), inclusive; default last 30 days.")
    public ResponseEntity<byte[]> statement(
            @AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw com.quantgen.common.error.ApiException.fieldError(HttpStatus.BAD_REQUEST, "format",
                    "only csv is supported");
        }
        String csv = accounts.statementCsv(user.id(), id, from, to);
        String filename = "quantgen-statement-" + (from != null ? from : "recent") + ".csv";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
