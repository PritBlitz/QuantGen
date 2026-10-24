package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.autopilot.RuleDsl.Action;
import com.quantgen.autopilot.RuleDsl.Amount;
import com.quantgen.autopilot.RuleDsl.Caps;
import com.quantgen.autopilot.RuleDsl.Condition;
import com.quantgen.autopilot.RuleDsl.Trigger;
import com.quantgen.common.error.ApiError.FieldError;
import com.quantgen.common.web.TextRules;
import com.quantgen.payment.service.BillService;

import tools.jackson.databind.JsonNode;

/**
 * F-AUTO-1 validation of a rule against the DSL and the user's own data: accounts must be theirs, beneficiaries
 * saved more than 24 h ago, symbols real, caps set. Errors point into the DSL ({@code rule.actions[0].toAccountId}),
 * as RULE_INVALID field errors. A valid rule comes back normalized (e.g. a transfer's source filled in).
 */
@Component
class RuleValidator {

    static final BigDecimal MAX_CAP = new BigDecimal("100000.00");
    static final Duration PAYEE_AGE = Duration.ofHours(24);
    static final List<String> TRIGGERS = List.of("MONEY_RECEIVED", "SCHEDULE", "BALANCE_ABOVE", "PRICE_CROSSES",
            "BUDGET_THRESHOLD");
    static final List<String> ACTIONS = List.of("TRANSFER", "BUY", "NOTIFY", "ROUND_UP_SWEEP", "PAY_BILL");
    static final List<Integer> ROUND_TO = List.of(10, 50, 100);
    static final List<String> WEEKDAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    static final List<String> CREDIT_TYPES = List.of("DEPOSIT", "TRANSFER", "TOPUP", "INTEREST", "REFUND", "LOAN_DISBURSAL");

    record Result(RuleDsl rule, List<FieldError> errors) {

        boolean valid() {
            return errors.isEmpty();
        }
    }

    private final AccountService accounts;
    private final BillService bills;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    RuleValidator(AccountService accounts, BillService bills, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.bills = bills;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Result validate(UUID userId, JsonNode r) {
        List<FieldError> fe = new ArrayList<>();
        if (r == null || !r.isObject()) {
            return new Result(null, List.of(new FieldError("rule", "is required")));
        }
        if (!r.path("dslVersion").isIntegralNumber() || r.path("dslVersion").asInt() != RuleDsl.VERSION) {
            return new Result(null, List.of(new FieldError("rule.dslVersion", "must be 1")));
        }
        Trigger trigger = trigger(userId, r.path("trigger"), fe);

        List<Condition> conditions = new ArrayList<>();
        JsonNode cs = r.path("conditions");
        if (!cs.isMissingNode() && !cs.isNull()) {
            if (!cs.isArray() || cs.size() > 3) {
                fe.add(new FieldError("rule.conditions", "at most 3 conditions"));
            } else {
                for (int i = 0; i < cs.size(); i++) {
                    String p = "rule.conditions[" + i + "]";
                    JsonNode c = cs.get(i);
                    if (!"BALANCE_AT_LEAST".equals(c.path("type").asString(""))) {
                        fe.add(new FieldError(p + ".type", "must be BALANCE_AT_LEAST"));
                        continue;
                    }
                    UUID acc = ownAccount(userId, c, "accountId", p, fe, false);
                    BigDecimal amt = money(c, "amount", p, BigDecimal.ZERO, new BigDecimal("100000000.00"), fe);
                    conditions.add(new Condition("BALANCE_AT_LEAST", acc, amt));
                }
            }
        }

        List<Action> actions = new ArrayList<>();
        JsonNode as = r.path("actions");
        if (!as.isArray() || as.isEmpty() || as.size() > 5) {
            fe.add(new FieldError("rule.actions", "add 1–5 actions"));
        } else {
            for (int i = 0; i < as.size(); i++) {
                Action a = action(userId, as.get(i), "rule.actions[" + i + "]", trigger, fe);
                if (a != null) {
                    actions.add(a);
                }
            }
        }

        Caps caps = null;
        JsonNode c = r.path("caps");
        if (!c.isObject()) {
            fe.add(new FieldError("rule.caps", "set both caps"));
        } else {
            BigDecimal perRun = money(c, "perRun", "rule.caps", new BigDecimal("1.00"), MAX_CAP, fe);
            BigDecimal perMonth = money(c, "perMonth", "rule.caps", new BigDecimal("1.00"), new BigDecimal("3000000.00"), fe);
            if (perRun != null && perMonth != null) {
                if (perRun.compareTo(perMonth) > 0) {
                    fe.add(new FieldError("rule.caps.perRun", "can't be more than the monthly cap"));
                }
                caps = new Caps(perRun, perMonth);
            }
        }
        return fe.isEmpty() ? new Result(new RuleDsl(RuleDsl.VERSION, trigger, conditions, actions, caps), List.of())
                : new Result(null, fe);
    }

    // ------------------------------------------------------------------ parts

    private Trigger trigger(UUID userId, JsonNode t, List<FieldError> fe) {
        String p = "rule.trigger";
        if (!t.isObject()) {
            fe.add(new FieldError(p, "is required"));
            return null;
        }
        String type = t.path("type").asString("");
        if (!TRIGGERS.contains(type)) {
            fe.add(new FieldError(p + ".type", "must be one of " + String.join(", ", TRIGGERS)));
            return null;
        }
        return switch (type) {
            case "MONEY_RECEIVED" -> {
                UUID acc = ownAccount(userId, t, "accountId", p, fe, false);
                BigDecimal min = t.hasNonNull("minAmount")
                        ? money(t, "minAmount", p, new BigDecimal("0.01"), new BigDecimal("100000000.00"), fe) : null;
                String contains = optionalText(t, "counterpartyContains", p, 40, fe);
                String txType = optionalText(t, "transactionType", p, 20, fe);
                if (txType != null && !CREDIT_TYPES.contains(txType)) {
                    fe.add(new FieldError(p + ".transactionType", "must be one of " + String.join(", ", CREDIT_TYPES)));
                }
                yield new Trigger(type, acc, min, contains, txType, null, null, null, null, null, null, null, null, null);
            }
            case "SCHEDULE" -> {
                String freq = t.path("frequency").asString("");
                if (freq.equals("MONTHLY")) {
                    JsonNode d = t.path("dayOfMonth");
                    if (!d.isIntegralNumber() || d.asInt() < 1 || d.asInt() > 28) {
                        fe.add(new FieldError(p + ".dayOfMonth", "must be 1–28"));
                    }
                    yield new Trigger(type, null, null, null, null, freq, d.asInt(), null, null, null, null, null, null, null);
                } else if (freq.equals("WEEKLY")) {
                    String wd = t.path("weekday").asString("");
                    if (!WEEKDAYS.contains(wd)) {
                        fe.add(new FieldError(p + ".weekday", "must be one of " + String.join(", ", WEEKDAYS)));
                    }
                    yield new Trigger(type, null, null, null, null, freq, null, wd, null, null, null, null, null, null);
                }
                fe.add(new FieldError(p + ".frequency", "must be MONTHLY or WEEKLY"));
                yield null;
            }
            case "BALANCE_ABOVE" -> {
                UUID acc = ownAccount(userId, t, "accountId", p, fe, true);
                BigDecimal threshold = money(t, "threshold", p, BigDecimal.ZERO, new BigDecimal("100000000.00"), fe);
                yield new Trigger(type, acc, null, null, null, null, null, null, threshold, null, null, null, null, null);
            }
            case "BUDGET_THRESHOLD" -> {
                String category = t.path("category").asString("");
                List<BigDecimal> budget = jdbc.queryForList("SELECT monthly_limit FROM budgets WHERE user_id = ? "
                        + "AND category = ?", BigDecimal.class, userId, category);
                if (budget.isEmpty()) {
                    fe.add(new FieldError(p + ".category", "set a monthly budget for this category first"));
                }
                JsonNode pct = t.path("percent");
                if (!pct.isIntegralNumber() || pct.asInt() < 1 || pct.asInt() > 200) {
                    fe.add(new FieldError(p + ".percent", "must be a whole percentage 1–200"));
                }
                yield new Trigger(type, null, null, null, null, null, null, null, null, null, null, null, category,
                        pct.asInt());
            }
            default -> { // PRICE_CROSSES
                String symbol = symbol(t, p, fe);
                String dir = t.path("direction").asString("");
                if (!dir.equals("ABOVE") && !dir.equals("BELOW")) {
                    fe.add(new FieldError(p + ".direction", "must be ABOVE or BELOW"));
                }
                BigDecimal price = money(t, "price", p, new BigDecimal("0.01"), new BigDecimal("10000000.00"), fe);
                yield new Trigger(type, null, null, null, null, null, null, null, null, symbol, dir, price, null, null);
            }
        };
    }

    private Action action(UUID userId, JsonNode a, String p, Trigger trigger, List<FieldError> fe) {
        String type = a.path("type").asString("");
        if (!ACTIONS.contains(type)) {
            fe.add(new FieldError(p + ".type", "must be one of " + String.join(", ", ACTIONS)));
            return null;
        }
        switch (type) {
            case "TRANSFER" -> {
                UUID from;
                if (a.hasNonNull("fromAccountId")) {
                    from = ownAccount(userId, a, "fromAccountId", p, fe, false);
                } else if (trigger != null && trigger.accountId() != null) {
                    from = trigger.accountId(); // default: the account the trigger watches
                } else {
                    fe.add(new FieldError(p + ".fromAccountId", "choose the account the money comes from"));
                    from = null;
                }
                Optional<AccountRef> fromAcc = from == null ? Optional.empty() : accounts.findById(from);
                if (fromAcc.isPresent() && fromAcc.get().type() == AccountType.FIXED_DEPOSIT) {
                    fe.add(new FieldError(p + ".fromAccountId", "a fixed deposit can't send money"));
                }
                boolean toAccount = a.hasNonNull("toAccountId");
                boolean toPayee = a.hasNonNull("toBeneficiaryId");
                UUID to = null;
                UUID payee = null;
                if (toAccount == toPayee) {
                    fe.add(new FieldError(p, "choose where the money goes (one of your accounts or a saved payee)"));
                } else if (toAccount) {
                    to = ownAccount(userId, a, "toAccountId", p, fe, false);
                    Optional<AccountRef> toAcc = to == null ? Optional.empty() : accounts.findById(to);
                    if (toAcc.isPresent() && toAcc.get().type() == AccountType.FIXED_DEPOSIT) {
                        fe.add(new FieldError(p + ".toAccountId", "a fixed deposit can't receive money"));
                    }
                    if (to != null && to.equals(from)) {
                        fe.add(new FieldError(p + ".toAccountId", "must be different from the source account"));
                    }
                } else {
                    payee = beneficiary(userId, a, p, fe);
                    if (fromAcc.isPresent() && !fromAcc.get().isEveryday()) {
                        fe.add(new FieldError(p + ".fromAccountId", "goals only move money to your own accounts"));
                    }
                }
                Amount amount = amount(a, p, trigger, fe);
                return new Action(type, from, to, payee, null, null, amount, null, null, null);
            }
            case "BUY" -> {
                UUID acc = ownAccount(userId, a, "accountId", p, fe, true);
                String symbol = symbol(a, p, fe);
                Amount amount = amount(a, p, trigger, fe);
                return new Action(type, null, null, null, acc, symbol, amount, null, null, null);
            }
            case "ROUND_UP_SWEEP" -> {
                // The window is "since the previous scheduled day", so it needs a schedule to have one.
                if (trigger != null && !"SCHEDULE".equals(trigger.type())) {
                    fe.add(new FieldError(p + ".type", "round-up sweeps run on a schedule trigger"));
                }
                UUID from = ownAccount(userId, a, "fromAccountId", p, fe, true);
                UUID to = ownAccount(userId, a, "toAccountId", p, fe, false);
                Optional<AccountRef> toAcc = to == null ? Optional.empty() : accounts.findById(to);
                if (toAcc.isPresent() && toAcc.get().type() == AccountType.FIXED_DEPOSIT) {
                    fe.add(new FieldError(p + ".toAccountId", "a fixed deposit can't receive money"));
                }
                if (to != null && to.equals(from)) {
                    fe.add(new FieldError(p + ".toAccountId", "must be different from the source account"));
                }
                if (from != null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM round_up_settings "
                        + "WHERE account_id = ? AND enabled)", Boolean.class, from))) {
                    // Both would save the same spare change twice.
                    fe.add(new FieldError(p + ".fromAccountId", "this account already saves its round-ups every night"));
                }
                JsonNode r = a.path("roundTo");
                if (!r.isIntegralNumber() || !ROUND_TO.contains(r.asInt())) {
                    fe.add(new FieldError(p + ".roundTo", "must be 10, 50 or 100"));
                }
                return new Action(type, from, to, null, null, null, null, null, r.asInt(), null);
            }
            case "PAY_BILL" -> {
                UUID from = ownAccount(userId, a, "fromAccountId", p, fe, true);
                UUID saved = uuid(a, "savedBillerId");
                if (saved == null || bills.savedBiller(userId, saved).isEmpty()) {
                    fe.add(new FieldError(p + ".savedBillerId", "pick one of your saved billers"));
                }
                return new Action(type, from, null, null, null, null, null, null, null, saved);
            }
            default -> { // NOTIFY
                String message = optionalText(a, "message", p, 140, fe);
                if (message == null) {
                    fe.add(new FieldError(p + ".message", "write the message (up to 140 characters)"));
                }
                return new Action(type, null, null, null, null, null, null, message, null, null);
            }
        }
    }

    private Amount amount(JsonNode a, String p, Trigger trigger, List<FieldError> fe) {
        JsonNode n = a.path("amount");
        String mode = n.path("mode").asString("");
        switch (mode) {
            case "FIXED" -> {
                return new Amount(mode, money(n, "value", p + ".amount", new BigDecimal("1.00"), MAX_CAP, fe));
            }
            case "PERCENT_OF_TRIGGER" -> {
                if (trigger != null && !"MONEY_RECEIVED".equals(trigger.type())) {
                    fe.add(new FieldError(p + ".amount", "\"% of it\" only works with a money-received trigger"));
                }
                BigDecimal v = decimal(n, "value");
                if (v == null || v.scale() > 2 || v.signum() <= 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) {
                    fe.add(new FieldError(p + ".amount.value", "must be a percentage above 0 and at most 100"));
                }
                return new Amount(mode, v);
            }
            case "EXCESS_ABOVE" -> {
                return new Amount(mode, money(n, "value", p + ".amount", BigDecimal.ZERO, new BigDecimal("100000000.00"), fe));
            }
            default -> {
                fe.add(new FieldError(p + ".amount.mode", "must be FIXED, PERCENT_OF_TRIGGER or EXCESS_ABOVE"));
                return null;
            }
        }
    }

    // ------------------------------------------------------------------ field helpers

    private UUID ownAccount(UUID userId, JsonNode n, String field, String p, List<FieldError> fe, boolean everyday) {
        UUID id = uuid(n, field);
        Optional<AccountRef> acc = id == null ? Optional.empty()
                : accounts.findById(id).filter(x -> x.userId().equals(userId) && x.isActive());
        if (acc.isEmpty()) {
            fe.add(new FieldError(p + "." + field, "pick one of your accounts"));
            return null;
        }
        if (everyday && !acc.get().isEveryday()) {
            fe.add(new FieldError(p + "." + field, "pick a savings or checking account"));
        }
        return id;
    }

    private UUID beneficiary(UUID userId, JsonNode a, String p, List<FieldError> fe) {
        UUID id = uuid(a, "toBeneficiaryId");
        List<Timestamp> saved = id == null ? List.of() : jdbc.queryForList(
                "SELECT created_at FROM beneficiaries WHERE id = ? AND user_id = ?", Timestamp.class, id, userId);
        if (saved.isEmpty()) {
            fe.add(new FieldError(p + ".toBeneficiaryId", "pick a saved payee"));
            return null;
        }
        if (saved.getFirst().toInstant().isAfter(clock.instant().minus(PAYEE_AGE))) {
            fe.add(new FieldError(p + ".toBeneficiaryId", "payees saved in the last 24 hours can't be used yet"));
        }
        return id;
    }

    private String symbol(JsonNode n, String p, List<FieldError> fe) {
        String s = n.path("symbol").asString("").trim().toUpperCase(java.util.Locale.ROOT);
        Boolean known = !s.matches("^[A-Z]{1,10}$") ? Boolean.FALSE
                : jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM instruments WHERE symbol = ?)", Boolean.class, s);
        if (!Boolean.TRUE.equals(known)) {
            fe.add(new FieldError(p + ".symbol", "unknown symbol"));
        }
        return s;
    }

    private static BigDecimal money(JsonNode n, String field, String p, BigDecimal min, BigDecimal max, List<FieldError> fe) {
        BigDecimal v = n.path(field).isString() ? decimal(n, field) : null;
        if (v == null || v.scale() > 2 || v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            fe.add(new FieldError(p + "." + field, "must be an amount (a string) from " + min.toPlainString() + " to "
                    + max.toPlainString()));
            return null;
        }
        return v.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        try {
            return v.isString() ? new BigDecimal(v.asString()) : v.isNumber() ? v.decimalValue() : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID uuid(JsonNode n, String field) {
        try {
            return n.hasNonNull(field) ? UUID.fromString(n.path(field).asString("")) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String optionalText(JsonNode n, String field, String p, int max, List<FieldError> fe) {
        if (!n.hasNonNull(field)) {
            return null;
        }
        String s = n.path(field).isString() ? n.path(field).asString().strip() : null;
        if (s == null || s.length() > max || !s.matches(TextRules.NO_CONTROL_CHARS)) {
            fe.add(new FieldError(p + "." + field, "must be text up to " + max + " characters"));
            return null;
        }
        return s.isEmpty() ? null : s;
    }
}
