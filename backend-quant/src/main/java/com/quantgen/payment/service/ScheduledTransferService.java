package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.payment.api.PaymentDtos.TransferDto;
import com.quantgen.payment.domain.TransferSource;

/**
 * F-PAY-9 scheduled and recurring transfers. A run happens on the occurrence date at 07:00 IST and goes through the
 * normal transfer rules (limits, cooling) with {@code source = SCHEDULED}. Nobody is present to answer a step-up
 * prompt then, so an instruction above the step-up threshold needs step-up when it is <em>created</em>. A failed run
 * is retried once the next day, then recorded as MISSED with a notification, and the instruction moves on.
 *
 * <p>Exactly-once: each attempt is keyed by (instruction, occurrence, attempt), both in the run table and as the
 * idempotency key of the transfer, so an overlapping or repeated job run can't pay an occurrence twice.
 */
@Service
public class ScheduledTransferService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferService.class);

    public static final int MAX_ACTIVE = 25;
    public static final int MAX_COUNT = 120;

    public enum Frequency { ONCE, WEEKLY, MONTHLY }

    public record LastRun(LocalDate date, String outcome, UUID transferId) {
    }

    public record ScheduledTransferDto(UUID id, UUID fromAccountId, String toAccountNumberMasked, String toName,
                                       BigDecimal amount, String note, Frequency frequency, LocalDate nextRunDate,
                                       LocalDate endDate, Integer remainingCount, String status, LastRun lastRun) {
    }

    public record CreateCommand(UUID fromAccountId, String toAccountNumber, String toHandle, BigDecimal amount,
                                String note, LocalDate startDate, Frequency frequency, LocalDate endDate,
                                Integer count) {
    }

    private record Row(UUID id, UUID userId, UUID fromAccountId, UUID toAccountId, String toAccountNumber,
                       String toNameMasked, BigDecimal amount, String note, Frequency frequency, LocalDate endDate,
                       Integer remainingCount, LocalDate nextRunDate, LocalDate retryOn, String status,
                       LocalDate lastRunDate, String lastOutcome, UUID lastTransferId) {
    }

    private static final String COLUMNS = """
            id, user_id, from_account_id, to_account_id, to_account_number, to_name_masked, amount, note, frequency,
            end_date, remaining_count, next_run_date, retry_on, status, last_run_date, last_outcome, last_transfer_id
            """;

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final UserService users;
    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final StepUpService stepUp;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ScheduledTransferService(JdbcTemplate jdbc, AccountService accounts, UserService users,
                                    TransferService transfers, IdempotencyService idempotency, StepUpService stepUp,
                                    NotificationService notifications, Clock clock,
                                    PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.users = users;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.stepUp = stepUp;
        this.notifications = notifications;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** A validated instruction: the resolved accounts and the normalized amount and note. */
    public record Checked(AccountRef from, AccountRef to, BigDecimal amount, String note) {
    }

    @Transactional
    public IdempotencyService.Result<ScheduledTransferDto> create(UUID userId, UUID idempotencyKey, String fingerprint,
                                                                  CreateCommand cmd, String stepUpToken) {
        return create(userId, idempotencyKey, fingerprint, cmd, stepUpToken, false);
    }

    /** {@code preAuthorized}: the customer already completed step-up for this instruction (an approved intent). */
    @Transactional
    public IdempotencyService.Result<ScheduledTransferDto> create(UUID userId, UUID idempotencyKey, String fingerprint,
                                                                  CreateCommand cmd, String stepUpToken,
                                                                  boolean preAuthorized) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, ScheduledTransferDto.class,
                () -> doCreate(userId, cmd, stepUpToken, preAuthorized));
    }

    private ScheduledTransferDto doCreate(UUID userId, CreateCommand cmd, String stepUpToken, boolean preAuthorized) {
        Checked c = check(userId, cmd);
        AccountRef from = c.from();
        AccountRef to = c.to();
        BigDecimal amount = c.amount();
        LocalDate start = cmd.startDate();
        if (!preAuthorized && !to.userId().equals(userId) && amount.compareTo(TransferService.STEP_UP_THRESHOLD) > 0) {
            // Runs happen with nobody present, so the customer confirms a large instruction now.
            stepUp.require(userId, StepUpAction.TRANSFER, stepUpToken);
        }
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
        Integer active = jdbc.queryForObject("SELECT count(*) FROM scheduled_transfers WHERE user_id = ? "
                + "AND status IN ('ACTIVE', 'PAUSED')", Integer.class, userId);
        if (active != null && active >= MAX_ACTIVE) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "You can have at most " + MAX_ACTIVE + " scheduled transfers; cancel one first");
        }
        Integer remaining = cmd.frequency() == Frequency.ONCE ? Integer.valueOf(1) : cmd.count();
        UUID id = jdbc.queryForObject("""
                INSERT INTO scheduled_transfers (user_id, from_account_id, to_account_id, to_account_number,
                    to_name_masked, amount, note, frequency, start_date, end_date, remaining_count, next_run_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, userId, from.id(), to.id(), to.accountNumber(),
                HandleService.maskName(users.getUser(to.userId()).fullName()), amount, c.note(), cmd.frequency().name(),
                Date.valueOf(start), cmd.endDate() == null ? null : Date.valueOf(cmd.endDate()), remaining,
                Date.valueOf(start));
        return toDto(find(id, userId).orElseThrow());
    }

    /** Every rule a new instruction must pass, without storing anything (also validates an intent's payload). */
    @Transactional(readOnly = true)
    public Checked check(UUID userId, CreateCommand cmd) {
        AccountRef from = accounts.requireOwned(userId, cmd.fromAccountId());
        TransferService.Target target = transfers.resolveTarget(cmd.toAccountNumber(), cmd.toHandle());
        AccountRef to = target.account();
        if (to.id().equals(from.id())) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(), "must be different from the source account");
        }
        if (to.status() == AccountStatus.CLOSED) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(), "this account cannot receive money");
        }
        if (from.status() == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot send money");
        }
        if (!to.userId().equals(userId) && !from.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fromAccountId", "goals and fixed deposits can only move money to your own accounts");
        }
        if (!to.userId().equals(userId) && !to.isEveryday()) {
            // Same answer as an unknown number or handle, so someone else's goal or FD can't be probed for.
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(),
                    target.field().equals("toHandle") ? "no account with this handle" : "no account with this number");
        }
        if (from.type() == AccountType.FIXED_DEPOSIT) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fromAccountId",
                    "a fixed deposit pays out when it matures or is closed");
        }
        if (to.type() == AccountType.FIXED_DEPOSIT) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(), "this account cannot receive money");
        }
        LocalDate today = today();
        LocalDate start = cmd.startDate();
        if (!start.isAfter(today)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "startDate", "must be a future date");
        }
        if (start.isAfter(today.plusYears(1))) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "startDate", "must be within one year");
        }
        if (cmd.frequency() == Frequency.MONTHLY && start.getDayOfMonth() > 28) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "startDate", "monthly transfers run on day 1-28");
        }
        if (cmd.frequency() == Frequency.ONCE && (cmd.endDate() != null || cmd.count() != null)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, cmd.endDate() != null ? "endDate" : "count",
                    "only applies to WEEKLY or MONTHLY");
        }
        if (cmd.endDate() != null && !cmd.endDate().isAfter(start)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "endDate", "must be after the start date");
        }
        BigDecimal amount = Money.normalize(cmd.amount());
        String note = cmd.note() == null || cmd.note().isBlank() ? null : cmd.note().trim();
        return new Checked(from, to, amount, note);
    }

    @Transactional(readOnly = true)
    public List<ScheduledTransferDto> list(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM scheduled_transfers WHERE user_id = ? "
                + "ORDER BY created_at DESC, id DESC", (rs, i) -> map(rs), userId).stream().map(this::toDto).toList();
    }

    @Transactional
    public ScheduledTransferDto pause(UUID userId, UUID id) {
        Row row = lockOwned(userId, id);
        if (!"ACTIVE".equals(row.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only an active scheduled transfer can be paused");
        }
        jdbc.update("UPDATE scheduled_transfers SET status = 'PAUSED', updated_at = now() WHERE id = ?", id);
        return toDto(find(id, userId).orElseThrow());
    }

    /** Resuming skips the occurrences that fell inside the pause; they are not paid late. */
    @Transactional
    public ScheduledTransferDto resume(UUID userId, UUID id) {
        Row row = lockOwned(userId, id);
        if (!"PAUSED".equals(row.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a paused scheduled transfer can be resumed");
        }
        LocalDate today = today();
        LocalDate next = row.nextRunDate();
        while (!next.isAfter(today)) {
            next = row.frequency() == Frequency.ONCE ? today.plusDays(1) : advance(next, row.frequency());
        }
        boolean ended = row.endDate() != null && next.isAfter(row.endDate());
        jdbc.update("UPDATE scheduled_transfers SET status = ?, next_run_date = ?, retry_on = NULL, updated_at = now() "
                + "WHERE id = ?", ended ? "COMPLETED" : "ACTIVE", ended ? null : Date.valueOf(next), id);
        return toDto(find(id, userId).orElseThrow());
    }

    /** Cancelling twice is fine; a finished instruction can't be cancelled. */
    @Transactional
    public void cancel(UUID userId, UUID id) {
        Row row = lockOwned(userId, id);
        if ("CANCELLED".equals(row.status())) {
            return;
        }
        if ("COMPLETED".equals(row.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This scheduled transfer has already completed");
        }
        jdbc.update("UPDATE scheduled_transfers SET status = 'CANCELLED', next_run_date = NULL, retry_on = NULL, "
                + "updated_at = now() WHERE id = ?", id);
    }

    // ------------------------------------------------------------------ runs

    /** Runs every instruction due on or before {@code today} (one attempt each). Returns how many were attempted. */
    public int runDue(LocalDate today) {
        List<UUID> due = jdbc.queryForList("SELECT id FROM scheduled_transfers WHERE status = 'ACTIVE' "
                + "AND COALESCE(retry_on, next_run_date) <= ? ORDER BY next_run_date, id", UUID.class, Date.valueOf(today));
        int attempted = 0;
        for (UUID id : due) {
            try {
                if (runOne(id, today)) {
                    attempted++;
                }
            } catch (RuntimeException e) {
                // Left as is; the next run picks it up again (the derived idempotency key prevents a double payment).
                log.error("Scheduled transfer {} could not be processed", id, e);
            }
        }
        return attempted;
    }

    private boolean runOne(UUID id, LocalDate today) {
        try {
            Boolean ran = tx.execute(status -> {
                Row row = lockDue(id, today);
                if (row == null) {
                    return false;
                }
                int attempt = row.retryOn() == null ? 1 : 2;
                UUID key = UUID.nameUUIDFromBytes(("scheduled:" + id + ":" + row.nextRunDate() + ":" + attempt)
                        .getBytes(StandardCharsets.UTF_8));
                TransferDto transfer = idempotency.execute(row.userId(), key,
                        "SCHEDULED " + id + " " + row.nextRunDate() + " " + attempt, 201, TransferDto.class,
                        () -> transfers.execute(row.userId(), row.fromAccountId(),
                                new TransferService.Target(accountOf(row), "toAccountNumber"), row.amount(),
                                row.note(), TransferSource.SCHEDULED, null, true)).body();
                recordRun(row, attempt, today, "SUCCEEDED", transfer.id(), null);
                moveOn(row, today, "SUCCEEDED", transfer.id());
                return true;
            });
            return Boolean.TRUE.equals(ran);
        } catch (ApiException failure) {
            Boolean ran = tx.execute(status -> {
                Row row = lockDue(id, today);
                if (row == null) {
                    return false;
                }
                int attempt = row.retryOn() == null ? 1 : 2;
                String what = Money.toJson(row.amount()) + " INR to " + row.toNameMasked() + " "
                        + AccountNumbers.mask(row.toAccountNumber());
                if (attempt == 1) {
                    recordRun(row, attempt, today, "RETRYING", null, failure.code().name());
                    jdbc.update("UPDATE scheduled_transfers SET retry_on = ?, last_run_date = ?, last_outcome = 'RETRYING', "
                            + "last_transfer_id = NULL, updated_at = now() WHERE id = ?",
                            Date.valueOf(today.plusDays(1)), Date.valueOf(today), id);
                    notifications.notify(row.userId(), NotificationType.SCHEDULED_FAILED, "Scheduled transfer delayed",
                            "Your scheduled transfer of " + what + " didn't go through (" + reason(failure)
                                    + "). We'll try once more tomorrow.");
                } else {
                    recordRun(row, attempt, today, "MISSED", null, failure.code().name());
                    moveOn(row, today, "MISSED", null);
                    notifications.notify(row.userId(), NotificationType.SCHEDULED_FAILED, "Scheduled transfer missed",
                            "Your scheduled transfer of " + what + " was missed (" + reason(failure) + ").");
                }
                return true;
            });
            return Boolean.TRUE.equals(ran);
        }
    }

    private static String reason(ApiException e) {
        return switch (e.code()) {
            case INSUFFICIENT_FUNDS -> "insufficient funds";
            case ACCOUNT_FROZEN -> "the account is frozen";
            case LIMIT_EXCEEDED -> "a transfer limit was reached";
            case COOLING_PERIOD -> "the new-payee limit applies";
            default -> "the payment was refused";
        };
    }

    private void recordRun(Row row, int attempt, LocalDate today, String outcome, UUID transferId, String errorCode) {
        jdbc.update("""
                INSERT INTO scheduled_transfer_runs (scheduled_transfer_id, occurrence_date, attempt, run_date, outcome,
                                                     transfer_id, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, row.id(), Date.valueOf(row.nextRunDate()), attempt, Date.valueOf(today), outcome, transferId,
                errorCode);
    }

    /** Counts the occurrence (paid or missed) and points at the next one, or completes the instruction. */
    private void moveOn(Row row, LocalDate today, String outcome, UUID transferId) {
        Integer remaining = row.remainingCount() == null ? null : row.remainingCount() - 1;
        LocalDate next = row.frequency() == Frequency.ONCE ? null : advance(row.nextRunDate(), row.frequency());
        if (remaining != null && remaining <= 0) {
            next = null;
        }
        if (next != null && row.endDate() != null && next.isAfter(row.endDate())) {
            next = null;
        }
        jdbc.update("""
                UPDATE scheduled_transfers SET next_run_date = ?, retry_on = NULL, remaining_count = ?, status = ?,
                       last_run_date = ?, last_outcome = ?, last_transfer_id = ?, updated_at = now()
                 WHERE id = ?
                """, next == null ? null : Date.valueOf(next), remaining, next == null ? "COMPLETED" : "ACTIVE",
                Date.valueOf(today), outcome, transferId, row.id());
    }

    static LocalDate advance(LocalDate occurrence, Frequency frequency) {
        return switch (frequency) {
            case WEEKLY -> occurrence.plusWeeks(1);
            // Monthly instructions run on day 1-28, so the day of month never shifts.
            case MONTHLY -> occurrence.plusMonths(1);
            case ONCE -> throw new IllegalArgumentException("a one-off transfer has no next occurrence");
        };
    }

    private AccountRef accountOf(Row row) {
        return accounts.findById(row.toAccountId()).orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE,
                "The destination account no longer exists"));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    // ------------------------------------------------------------------ rows

    private Row lockOwned(UUID userId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM scheduled_transfers WHERE id = ? AND user_id = ? FOR UPDATE",
                (rs, i) -> map(rs), id, userId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Scheduled transfer"));
    }

    /** The row if it is still due (re-checked under the lock); null if another run got to it first. */
    private Row lockDue(UUID id, LocalDate today) {
        return jdbc.query("SELECT " + COLUMNS + " FROM scheduled_transfers WHERE id = ? AND status = 'ACTIVE' "
                + "AND COALESCE(retry_on, next_run_date) <= ? FOR UPDATE SKIP LOCKED", (rs, i) -> map(rs), id,
                Date.valueOf(today)).stream().findFirst().orElse(null);
    }

    private Optional<Row> find(UUID id, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM scheduled_transfers WHERE id = ? AND user_id = ?",
                (rs, i) -> map(rs), id, userId).stream().findFirst();
    }

    private ScheduledTransferDto toDto(Row r) {
        LastRun lastRun = r.lastRunDate() == null ? null
                : new LastRun(r.lastRunDate(), r.lastOutcome(), r.lastTransferId());
        LocalDate shownNext = r.retryOn() != null ? r.retryOn() : r.nextRunDate();
        return new ScheduledTransferDto(r.id(), r.fromAccountId(), AccountNumbers.mask(r.toAccountNumber()),
                r.toNameMasked(), r.amount(), r.note(), r.frequency(), shownNext, r.endDate(), r.remainingCount(),
                r.status(), lastRun);
    }

    private static Row map(ResultSet rs) throws SQLException {
        Date end = rs.getDate("end_date");
        Date next = rs.getDate("next_run_date");
        Date retry = rs.getDate("retry_on");
        Date last = rs.getDate("last_run_date");
        return new Row(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("from_account_id", UUID.class), rs.getObject("to_account_id", UUID.class),
                rs.getString("to_account_number"), rs.getString("to_name_masked"), rs.getBigDecimal("amount"),
                rs.getString("note"), Frequency.valueOf(rs.getString("frequency")),
                end == null ? null : end.toLocalDate(), (Integer) rs.getObject("remaining_count"),
                next == null ? null : next.toLocalDate(), retry == null ? null : retry.toLocalDate(),
                rs.getString("status"), last == null ? null : last.toLocalDate(), rs.getString("last_outcome"),
                rs.getObject("last_transfer_id", UUID.class));
    }
}
