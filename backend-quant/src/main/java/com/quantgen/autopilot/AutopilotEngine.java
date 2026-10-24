package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.autopilot.RuleDsl.Action;
import com.quantgen.autopilot.RuleDsl.Condition;
import com.quantgen.autopilot.RuleDsl.Trigger;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.events.EventConsumer;
import com.quantgen.common.events.OutboxEvent;
import com.quantgen.common.flags.FeatureFlagService;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.Inr;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.insights.CategoryService;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.payment.api.PaymentDtos.TransferDto;
import com.quantgen.payment.domain.TransferSource;
import com.quantgen.payment.service.BillService;
import com.quantgen.payment.service.TransferService;
import com.quantgen.trading.api.TradingDtos.OrderDto;
import com.quantgen.trading.api.TradingDtos.OrderRequest;
import com.quantgen.trading.domain.OrderSide;
import com.quantgen.trading.service.OrderService;
import com.quantgen.trading.service.PricesUpdatedEvent;

import tools.jackson.databind.JsonNode;

/**
 * F-AUTO-5/6: the deterministic Autopilot engine (ARCHITECTURE 12.9). Triggers come from outbox MONEY_MOVED events,
 * the 08:00 IST scheduler and price ticks. A run is claimed once per (rule, version, trigger event) in a short
 * transaction that also checks conditions and caps; then each action runs in its own transaction through the normal
 * service with key {@code auto:<runId>:<index>}, and its result row commits with it, so a crash resumes without
 * repeating finished actions. A failed action stops the run. Three failed runs in a row pause the rule.
 * Autopilot-made transfers never fire MONEY_RECEIVED rules, so rules can't trigger each other.
 */
@Component
public class AutopilotEngine implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AutopilotEngine.class);

    /** F-AUTO-6: at most this much moved by Autopilot per user per IST day, across all rules. */
    static final BigDecimal DAILY_CAP = new BigDecimal("100000.00");
    static final int FAILURES_TO_PAUSE = 3;
    static final Duration RESUME_AFTER = Duration.ofMinutes(2);
    /** Journal types that count as spending for budgets (as for the budget alerts). */
    static final List<String> SPEND_TYPES = List.of("TRANSFER", "BILL_PAYMENT", "LOAN_REPAYMENT", "CARD");
    /** Action types that move money, so they count toward the caps. */
    static final String MOVING = "('TRANSFER', 'BUY', 'ROUND_UP_SWEEP', 'PAY_BILL')";

    record Claimed(UUID runId) {
    }

    private record ActiveRule(UUID id, UUID userId, String name, int version, RuleDsl dsl) {
    }

    private final JdbcTemplate jdbc;
    private final AutopilotService rules;
    private final RuleRenderer renderer;
    private final RuleInputs inputs;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final TransferService transfers;
    private final OrderService orders;
    private final BillService bills;
    private final IdempotencyService idempotency;
    private final NotificationService notifications;
    private final FeatureFlagService flags;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    AutopilotEngine(JdbcTemplate jdbc, AutopilotService rules, RuleRenderer renderer, RuleInputs inputs,
                    AccountService accounts,
                    LedgerService ledger, TransferService transfers, OrderService orders, BillService bills,
                    IdempotencyService idempotency,
                    NotificationService notifications, FeatureFlagService flags, Clock clock,
                    PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.renderer = renderer;
        this.inputs = inputs;
        this.accounts = accounts;
        this.ledger = ledger;
        this.transfers = transfers;
        this.orders = orders;
        this.bills = bills;
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.flags = flags;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    private boolean enabled() {
        return flags.isEnabled(AutopilotService.FLAG);
    }

    // ------------------------------------------------------------------ MONEY_RECEIVED (outbox)

    @Override
    public String name() {
        return "autopilot";
    }

    @Override
    public boolean accepts(String eventType) {
        return OutboxEvent.MONEY_MOVED.equals(eventType);
    }

    /** Runs inside the outbox consumer's transaction: claims runs only; actions start after it commits. */
    @Override
    public void handle(OutboxEvent event) {
        if (!enabled()) {
            return;
        }
        JsonNode p = event.payload();
        UUID entryId = UUID.fromString(p.path("entryId").asString());
        String type = p.path("type").asString("");
        if ("REVERSAL".equals(type)) {
            return;
        }
        String source = jdbc.queryForList("SELECT source FROM transfers WHERE journal_entry_id = ?", String.class, entryId)
                .stream().findFirst().orElse(null);
        if (TransferSource.AUTOPILOT.name().equals(source)) {
            return; // F-AUTO-6: no loops
        }
        List<UUID> claimed = new ArrayList<>();
        for (JsonNode line : p.path("lines")) {
            if (!"CREDIT".equals(line.path("direction").asString())) {
                continue;
            }
            UUID accountId = UUID.fromString(line.path("ledgerAccountId").asString());
            BigDecimal amount = new BigDecimal(line.path("amount").asString());
            for (ActiveRule rule : active("MONEY_RECEIVED", accountId)) {
                Trigger t = rule.dsl().trigger();
                if (!accountId.equals(t.accountId())
                        || (t.minAmount() != null && amount.compareTo(t.minAmount()) < 0)
                        || (t.transactionType() != null && !t.transactionType().equals(type))) {
                    continue;
                }
                String counterparty = jdbc.queryForList("SELECT counterparty FROM ledger_lines WHERE journal_entry_id = ? "
                        + "AND ledger_account_id = ?", String.class, entryId, accountId).stream().findFirst().orElse("");
                if (t.counterpartyContains() != null && (counterparty == null
                        || !counterparty.toLowerCase().contains(t.counterpartyContains().toLowerCase()))) {
                    continue;
                }
                String summary = Inr.format(amount) + (counterparty == null || counterparty.isBlank() ? "" : " " + counterparty)
                        + " into " + renderer.account(accountId);
                UUID run = claim(rule, "entry:" + entryId, summary, amount);
                if (run != null) {
                    claimed.add(run);
                }
            }
        }
        if (SPEND_TYPES.contains(type)) {
            LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            for (JsonNode line : p.path("lines")) {
                if ("DEBIT".equals(line.path("direction").asString())) {
                    UUID accountId = UUID.fromString(line.path("ledgerAccountId").asString());
                    for (ActiveRule rule : active("BUDGET_THRESHOLD", accountId)) {
                        UUID run = budgetCheck(rule, today);
                        if (run != null) {
                            claimed.add(run);
                        }
                    }
                }
            }
        }
        executeAfterCommit(claimed);
    }

    // ------------------------------------------------------------------ SCHEDULE and BALANCE_ABOVE (08:00 IST)

    @Scheduled(cron = "${qg.autopilot.daily-cron:0 0 8 * * *}", zone = "Asia/Kolkata")
    void daily() {
        try {
            runDaily(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
        } catch (RuntimeException e) {
            log.error("Autopilot daily run failed", e);
        }
    }

    /**
     * Claims and runs every SCHEDULE rule due on {@code day}, every BALANCE_ABOVE rule above its threshold and every
     * BUDGET_THRESHOLD rule whose budget is past its line.
     */
    public int runDaily(LocalDate day) {
        if (!enabled()) {
            return 0;
        }
        List<UUID> runs = new ArrayList<>();
        for (ActiveRule rule : active("SCHEDULE", null)) {
            Trigger t = rule.dsl().trigger();
            boolean due = "MONTHLY".equals(t.frequency()) ? day.getDayOfMonth() == t.dayOfMonth()
                    : day.getDayOfWeek() == weekday(t.weekday());
            if (due) {
                String summary = "MONTHLY".equals(t.frequency()) ? "Scheduled: " + RuleRenderer.ordinal(t.dayOfMonth())
                        + " of the month" : "Scheduled: every " + t.weekday().charAt(0) + t.weekday().substring(1).toLowerCase();
                UUID run = tx.execute(st -> claim(rule, "schedule:" + day, summary, null));
                if (run != null) {
                    runs.add(run);
                }
            }
        }
        for (ActiveRule rule : active("BALANCE_ABOVE", null)) {
            Trigger t = rule.dsl().trigger();
            BigDecimal balance = ledger.balancesOf(List.of(t.accountId())).getOrDefault(t.accountId(), BigDecimal.ZERO);
            if (balance.compareTo(t.threshold()) > 0) {
                String summary = "Daily check: " + renderer.account(t.accountId()) + " held " + Inr.format(balance);
                UUID run = tx.execute(st -> claim(rule, "balance:" + day, summary, balance));
                if (run != null) {
                    runs.add(run);
                }
            }
        }
        // Spending normally fires these from the outbox; this catches a lowered budget or a recategorized spend.
        for (ActiveRule rule : active("BUDGET_THRESHOLD", null)) {
            UUID run = tx.execute(st -> budgetCheck(rule, day));
            if (run != null) {
                runs.add(run);
            }
        }
        runs.forEach(this::executeRun);
        return runs.size();
    }

    // ------------------------------------------------------------------ PRICE_CROSSES (ticks)

    @EventListener
    void onPrices(PricesUpdatedEvent event) {
        if (!enabled()) {
            return;
        }
        LocalDate day = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        try {
            List<ActiveRule> watching = active("PRICE_CROSSES", null);
            if (watching.isEmpty()) {
                return;
            }
            for (ActiveRule rule : watching) {
                Trigger t = rule.dsl().trigger();
                BigDecimal price = event.prices().get(t.symbol());
                if (price == null) {
                    continue;
                }
                boolean crossed = "ABOVE".equals(t.direction()) ? price.compareTo(t.price()) > 0 : price.compareTo(t.price()) < 0;
                if (crossed) {
                    String summary = t.symbol() + " at " + Inr.format(price) + " (" + ("ABOVE".equals(t.direction())
                            ? "above " : "below ") + Inr.format(t.price()) + ")";
                    // At most once per IST day while the price stays past the line.
                    UUID run = tx.execute(st -> claim(rule, "price:" + t.symbol() + ":" + day, summary, price));
                    if (run != null) {
                        executor.submit(() -> executeRun(run));
                    }
                }
            }
        } catch (RuntimeException e) {
            log.error("Autopilot price check failed", e);
        }
    }

    /** Claims a BUDGET_THRESHOLD run once the month's spending is past the line: at most once per rule per month. */
    private UUID budgetCheck(ActiveRule rule, LocalDate day) {
        Trigger t = rule.dsl().trigger();
        RuleInputs.BudgetUse use = inputs.budgetUse(rule.userId(), t.category(), day);
        if (use == null || use.pct() < t.percent()) {
            return null;
        }
        String summary = CategoryService.label(t.category()) + " budget at " + use.pct() + "% (" + Inr.format(use.spent())
                + " of " + Inr.format(use.limit()) + ")";
        return claim(rule, "budget:" + t.category() + ":" + YearMonth.from(day), summary, use.spent());
    }

    // ------------------------------------------------------------------ claiming

    /**
     * In the caller's transaction: records the run (a duplicate trigger event is a no-op and returns null), unless a
     * condition is false. Caps are checked against the planned total; over a cap the run is recorded as SKIPPED_CAP.
     */
    UUID claim(ActiveRule rule, String eventId, String summary, BigDecimal triggerAmount) {
        RuleDsl dsl = rule.dsl();
        for (Condition c : dsl.conditions()) {
            BigDecimal bal = ledger.balancesOf(List.of(c.accountId())).getOrDefault(c.accountId(), BigDecimal.ZERO);
            if (bal.compareTo(c.amount()) < 0) {
                return null; // the rule didn't fire
            }
        }
        BigDecimal planned = BigDecimal.ZERO;
        for (Action a : dsl.actions()) {
            planned = planned.add(amountFor(dsl, a, triggerAmount, eventId));
        }
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO autopilot_runs (rule_id, user_id, rule_version, trigger_event_id, trigger_summary, trigger_amount,
                                            planned_total)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (rule_id, rule_version, trigger_event_id) DO NOTHING RETURNING id
                """, UUID.class, rule.id(), rule.userId(), rule.version(), eventId, truncate(summary, 200), triggerAmount,
                planned);
        if (inserted.isEmpty()) {
            return null;
        }
        UUID runId = inserted.getFirst();
        String capReason = capExceeded(rule, planned);
        if (capReason != null) {
            jdbc.update("UPDATE autopilot_runs SET outcome = 'SKIPPED_CAP', finished_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), runId);
            notifications.notify(rule.userId(), NotificationType.RULE_RUN, "Autopilot skipped a run",
                    "“" + rule.name() + "” didn't run: " + capReason + ".");
            return null;
        }
        return runId;
    }

    private String capExceeded(ActiveRule rule, BigDecimal planned) {
        if (planned.compareTo(rule.dsl().caps().perRun()) > 0) {
            return "it would move " + Inr.format(planned) + ", over the per-run cap of " + Inr.format(rule.dsl().caps().perRun());
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        BigDecimal month = moved("x.rule_id = ?", rule.id(), today.withDayOfMonth(1));
        if (month.add(planned).compareTo(rule.dsl().caps().perMonth()) > 0) {
            return "the monthly cap of " + Inr.format(rule.dsl().caps().perMonth()) + " would be exceeded";
        }
        BigDecimal day = moved("x.user_id = ?", rule.userId(), today);
        if (day.add(planned).compareTo(DAILY_CAP) > 0) {
            return "Autopilot moves at most " + Inr.format(DAILY_CAP) + " a day";
        }
        return null;
    }

    private BigDecimal moved(String who, UUID id, LocalDate since) {
        BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(sum(a.amount), 0) FROM autopilot_run_actions a "
                        + "JOIN autopilot_runs x ON x.id = a.run_id WHERE " + who + " AND a.status = 'DONE' "
                        + "AND a.type IN " + MOVING + " AND a.created_at >= ?", BigDecimal.class, id,
                Timestamp.from(since.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // ------------------------------------------------------------------ executing

    private void executeAfterCommit(List<UUID> runs) {
        if (runs.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runs.forEach(r -> executor.submit(() -> executeRun(r)));
                }
            });
        } else {
            runs.forEach(this::executeRun);
        }
    }

    /** Runs (or resumes) a claimed run; actions already recorded are not repeated. Safe to call twice. */
    public void executeRun(UUID runId) {
        try {
            doExecute(runId);
        } catch (RuntimeException e) {
            log.error("Autopilot run {} failed to execute; it will be resumed", runId, e);
        }
    }

    private void doExecute(UUID runId) {
        Map<String, Object> run = jdbc.queryForMap("SELECT r.rule_id, r.user_id, r.rule_version, r.trigger_amount, "
                + "r.trigger_event_id, r.finished_at, ru.name FROM autopilot_runs r JOIN autopilot_rules ru ON ru.id = r.rule_id WHERE r.id = ?", runId);
        if (run.get("finished_at") != null) {
            return;
        }
        UUID ruleId = (UUID) run.get("rule_id");
        UUID userId = (UUID) run.get("user_id");
        String ruleName = (String) run.get("name");
        BigDecimal triggerAmount = (BigDecimal) run.get("trigger_amount");
        String eventId = (String) run.get("trigger_event_id");
        RuleDsl dsl = rules.version(ruleId, ((Number) run.get("rule_version")).intValue());
        BigDecimal movedThisRun = jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM autopilot_run_actions "
                + "WHERE run_id = ? AND status = 'DONE' AND type IN " + MOVING, BigDecimal.class, runId);
        String failure = jdbc.queryForList("SELECT error FROM autopilot_run_actions WHERE run_id = ? AND status = 'FAILED'",
                String.class, runId).stream().findFirst().orElse(null);
        for (int i = 0; i < dsl.actions().size() && failure == null; i++) {
            int idx = i;
            Boolean done = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM autopilot_run_actions WHERE run_id = ? AND idx = ?)",
                    Boolean.class, runId, idx);
            if (Boolean.TRUE.equals(done)) {
                continue;
            }
            Action a = dsl.actions().get(i);
            BigDecimal amount = amountFor(dsl, a, triggerAmount, eventId);
            BigDecimal soFar = movedThisRun;
            try {
                BigDecimal moved = tx.execute(st -> runAction(runId, idx, userId, ruleName, a, amount, soFar, dsl));
                movedThisRun = movedThisRun.add(moved == null ? BigDecimal.ZERO : moved);
            } catch (ApiException e) {
                failure = e.getMessage();
                String error = truncate(failure, 200);
                tx.executeWithoutResult(st -> jdbc.update("INSERT INTO autopilot_run_actions (run_id, idx, type, amount, "
                        + "status, error) VALUES (?, ?, ?, ?, 'FAILED', ?) ON CONFLICT DO NOTHING", runId, idx, a.type(),
                        "NOTIFY".equals(a.type()) ? null : amount, error));
            }
        }
        String failed = failure;
        tx.executeWithoutResult(st -> finish(runId, ruleId, userId, ruleName, failed));
    }

    /** One action in its own transaction; its result row commits with it. Returns the amount moved. */
    private BigDecimal runAction(UUID runId, int idx, UUID userId, String ruleName, Action a, BigDecimal amount,
                                 BigDecimal movedThisRun, RuleDsl dsl) {
        UUID key = UUID.nameUUIDFromBytes(("auto:" + runId + ":" + idx).getBytes(StandardCharsets.UTF_8));
        String fingerprint = "AUTOPILOT " + runId + " " + idx;
        switch (a.type()) {
            case "NOTIFY" -> {
                notifications.notify(userId, NotificationType.RULE_RUN, "Autopilot: " + ruleName, a.message());
                record(runId, idx, a.type(), null, "DONE", null, null);
                return BigDecimal.ZERO;
            }
            case "TRANSFER", "ROUND_UP_SWEEP" -> {
                if (amount.compareTo(BigDecimal.ONE) < 0) {
                    record(runId, idx, a.type(), amount, "SKIPPED", null, null);
                    return BigDecimal.ZERO;
                }
                requireWithinRunCap(amount, movedThisRun, dsl);
                AccountRef to = a.toAccountId() != null ? accounts.findById(a.toAccountId()).orElseThrow(() ->
                        ApiException.notFound("Account")) : payee(userId, a.toBeneficiaryId());
                TransferDto t = idempotency.execute(userId, key, fingerprint, 201, TransferDto.class, () ->
                        transfers.execute(userId, a.fromAccountId(), new TransferService.Target(to, "toAccountId"), amount,
                                "Autopilot: " + ruleName, TransferSource.AUTOPILOT, null, true)).body();
                record(runId, idx, a.type(), amount, "DONE", t.id(), null);
                return amount;
            }
            case "PAY_BILL" -> {
                BillService.SavedBiller saved = bills.savedBiller(userId, a.savedBillerId()).orElseThrow(() ->
                        new ApiException(ErrorCode.NOT_FOUND, "The saved biller was removed"));
                if (amount.compareTo(BigDecimal.ONE) < 0) {
                    record(runId, idx, a.type(), null, "SKIPPED", null, null); // this cycle's bill is already paid
                    return BigDecimal.ZERO;
                }
                requireWithinRunCap(amount, movedThisRun, dsl);
                // Fetching pins this cycle's bill; paying it is idempotent on the action's key.
                BillService.BillDto bill = bills.fetch(userId, saved.biller().id(), saved.consumerNumber());
                bills.pay(userId, key, fingerprint, bill.billId(), a.fromAccountId());
                record(runId, idx, a.type(), bill.amount(), "DONE", null, null);
                return bill.amount();
            }
            default -> { // BUY: rupee amount → whole shares, rounded down
                BigDecimal price = jdbc.queryForObject("SELECT price FROM instruments WHERE symbol = ?", BigDecimal.class,
                        a.symbol());
                long qty = amount.divide(price, 0, RoundingMode.DOWN).longValue();
                if (qty < 1) {
                    throw new ApiException(ErrorCode.INVALID_STATE, Inr.format(amount)
                            + " is less than one share of " + a.symbol() + " (" + Inr.format(price) + ")");
                }
                BigDecimal estimate = price.multiply(BigDecimal.valueOf(qty));
                requireWithinRunCap(estimate, movedThisRun, dsl);
                OrderDto o = orders.place(userId, key, fingerprint, new OrderRequest(a.accountId(), a.symbol(),
                        OrderSide.BUY, qty)).body();
                BigDecimal total = o.total() == null ? estimate : o.total();
                record(runId, idx, a.type(), total, "DONE", null, o.id());
                return total;
            }
        }
    }

    private static void requireWithinRunCap(BigDecimal amount, BigDecimal movedThisRun, RuleDsl dsl) {
        if (movedThisRun.add(amount).compareTo(dsl.caps().perRun()) > 0) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "Over the per-run cap of "
                    + Inr.format(dsl.caps().perRun()));
        }
    }

    private AccountRef payee(UUID userId, UUID beneficiaryId) {
        List<UUID> acc = jdbc.queryForList("SELECT account_id FROM beneficiaries WHERE id = ? AND user_id = ?", UUID.class,
                beneficiaryId, userId);
        if (acc.isEmpty()) {
            throw new ApiException(ErrorCode.NOT_FOUND, "The saved payee was removed");
        }
        return accounts.findById(acc.getFirst()).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private void record(UUID runId, int idx, String type, BigDecimal amount, String status, UUID transferId, UUID orderId) {
        jdbc.update("INSERT INTO autopilot_run_actions (run_id, idx, type, amount, status, transfer_id, order_id) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", runId, idx, type, amount, status, transferId, orderId);
    }

    private void finish(UUID runId, UUID ruleId, UUID userId, String ruleName, String failure) {
        Integer done = jdbc.queryForObject("SELECT count(*) FROM autopilot_run_actions WHERE run_id = ? AND status = 'DONE'",
                Integer.class, runId);
        String outcome = failure == null ? "SUCCEEDED" : done != null && done > 0 ? "PARTIAL" : "FAILED";
        int updated = jdbc.update("UPDATE autopilot_runs SET outcome = ?, finished_at = ? WHERE id = ? AND finished_at IS NULL",
                outcome, Timestamp.from(clock.instant()), runId);
        if (updated == 0) {
            return; // finished by a concurrent resume
        }
        if ("FAILED".equals(outcome)) {
            Integer streak = jdbc.queryForObject("UPDATE autopilot_rules SET consecutive_failures = consecutive_failures + 1 "
                    + "WHERE id = ? RETURNING consecutive_failures", Integer.class, ruleId);
            if (streak != null && streak >= FAILURES_TO_PAUSE) {
                int paused = jdbc.update("UPDATE autopilot_rules SET status = 'PAUSED', paused_reason = ?, updated_at = now() "
                        + "WHERE id = ? AND status = 'ACTIVE'", "Paused after " + FAILURES_TO_PAUSE + " failed runs in a row.",
                        ruleId);
                if (paused > 0) {
                    notifications.notify(userId, NotificationType.RULE_PAUSED, "Autopilot rule paused",
                            "“" + ruleName + "” failed " + FAILURES_TO_PAUSE + " times in a row and was paused. Last error: "
                                    + truncate(failure, 200));
                }
            }
        } else {
            jdbc.update("UPDATE autopilot_rules SET consecutive_failures = 0 WHERE id = ?", ruleId);
        }
        if (failure != null) {
            notifications.notify(userId, NotificationType.RULE_RUN, "Autopilot run " + outcome.toLowerCase(),
                    "“" + ruleName + "”: " + truncate(failure, 300));
        }
    }

    /** Resumes runs a crash left unfinished. */
    @Scheduled(fixedDelayString = "${qg.autopilot.resume-sweep-millis:60000}")
    void resumeUnfinished() {
        try {
            List<UUID> stale = jdbc.queryForList("SELECT id FROM autopilot_runs WHERE finished_at IS NULL AND started_at < ? "
                    + "ORDER BY started_at LIMIT 50", UUID.class, Timestamp.from(clock.instant().minus(RESUME_AFTER)));
            stale.forEach(this::executeRun);
        } catch (RuntimeException e) {
            log.error("Autopilot resume sweep failed", e);
        }
    }

    // ------------------------------------------------------------------ amounts and rules

    /**
     * The amount an action moves now. EXCESS_ABOVE reads the live balance of its source account; a round-up sweep
     * sums the window ending on the scheduled day in {@code eventId}, so a resumed run gets the same amount.
     */
    BigDecimal amountFor(RuleDsl dsl, Action a, BigDecimal triggerAmount, String eventId) {
        if ("ROUND_UP_SWEEP".equals(a.type())) {
            LocalDate due = LocalDate.parse(eventId.substring("schedule:".length()));
            return inputs.roundUps(a.fromAccountId(), a.roundTo(), dsl.trigger(), due);
        }
        if ("PAY_BILL".equals(a.type())) {
            // This cycle's bill, or nothing when it's already paid (then the action is skipped, not failed).
            return inputs.billDue(a.savedBillerId(), LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
        }
        if (a.amount() == null) {
            return BigDecimal.ZERO;
        }
        return switch (a.amount().mode()) {
            case "FIXED" -> a.amount().value();
            case "PERCENT_OF_TRIGGER" -> triggerAmount == null ? BigDecimal.ZERO
                    : triggerAmount.multiply(a.amount().value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
            default -> {
                UUID source = a.fromAccountId() != null ? a.fromAccountId() : a.accountId();
                BigDecimal balance = ledger.balancesOf(List.of(source)).getOrDefault(source, BigDecimal.ZERO);
                BigDecimal excess = balance.subtract(a.amount().value()).setScale(2, RoundingMode.DOWN);
                yield excess.max(BigDecimal.ZERO);
            }
        };
    }

    /** Active rules with this trigger type; for MONEY_RECEIVED only the owner of {@code accountId}. */
    private List<ActiveRule> active(String triggerType, UUID accountId) {
        String byOwner = accountId == null ? "" : " AND r.user_id = (SELECT user_id FROM accounts WHERE id = ?)";
        Object[] args = accountId == null ? new Object[] {triggerType} : new Object[] {triggerType, accountId};
        return jdbc.query("SELECT r.id, r.user_id, r.name, r.version FROM autopilot_rules r WHERE r.status = 'ACTIVE' "
                        + "AND r.trigger_type = ?" + byOwner,
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    int version = rs.getInt("version");
                    return new ActiveRule(id, rs.getObject("user_id", UUID.class), rs.getString("name"), version,
                            rules.version(id, version));
                }, args);
    }

    private static DayOfWeek weekday(String w) {
        return switch (w) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
