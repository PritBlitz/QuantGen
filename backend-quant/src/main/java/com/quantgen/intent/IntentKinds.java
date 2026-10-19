package com.quantgen.intent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.autopilot.AutopilotService;
import com.quantgen.autopilot.RuleDsl;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Inr;
import com.quantgen.common.web.TextRules;
import com.quantgen.intent.IntentService.Line;
import com.quantgen.intent.IntentService.ResultRef;
import com.quantgen.intent.IntentService.Summary;
import com.quantgen.payment.domain.TransferSource;
import com.quantgen.payment.service.HandleService;
import com.quantgen.payment.service.TransferService;
import com.quantgen.savings.FixedDepositService;
import com.quantgen.savings.FixedDepositService.OnMaturity;
import com.quantgen.trading.api.TradingDtos.OrderRequest;
import com.quantgen.trading.domain.OrderSide;
import com.quantgen.trading.service.OrderService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-kind validation, server-side rendering (D-023) and execution of intents. The stored payload is the
 * validated, normalized one (for a transfer: the resolved account id, not the handle), so what the user approved
 * is exactly what runs. Execution goes through the same services as a manual action.
 */
@Component
class IntentKinds {

    static final List<String> SUPPORTED = List.of("TRANSFER", "GOAL_TRANSFER", "ORDER", "FD_BOOKING", "AUTOPILOT_RULE");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    static final BigDecimal STEP_UP_INTERNAL = new BigDecimal("50000.00");
    static final BigDecimal MEDIUM = new BigDecimal("10000.00");
    static final BigDecimal HIGH = new BigDecimal("50000.00");

    record Prepared(Map<String, Object> payload, Summary summary, String riskLevel, boolean requiresStepUp) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final UserService users;
    private final TransferService transfers;
    private final OrderService orders;
    private final FixedDepositService deposits;
    private final AutopilotService autopilot;
    private final JsonMapper json;

    IntentKinds(JdbcTemplate jdbc, AccountService accounts, UserService users, TransferService transfers,
                OrderService orders, FixedDepositService deposits, AutopilotService autopilot, JsonMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.users = users;
        this.transfers = transfers;
        this.orders = orders;
        this.deposits = deposits;
        this.autopilot = autopilot;
        this.json = json;
    }

    Prepared prepare(UUID userId, String kind, JsonNode p) {
        return switch (kind == null ? "" : kind) {
            case "TRANSFER" -> transfer(userId, p);
            case "GOAL_TRANSFER" -> goalTransfer(userId, p);
            case "ORDER" -> order(userId, p);
            case "FD_BOOKING" -> fdBooking(userId, p);
            case "AUTOPILOT_RULE" -> autopilotRule(userId, p);
            case "BILL_PAYMENT", "SCHEDULED_TRANSFER", "PAYMENT_REQUEST", "LOAN_APPLICATION" ->
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "kind", "not supported yet; supported: "
                            + String.join(", ", SUPPORTED));
            default -> throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "kind", "unknown intent kind");
        };
    }

    ResultRef execute(UUID userId, UUID intentId, String kind, JsonNode p) {
        // Each execution reuses a key derived from the intent, so a service-level replay can never run it twice.
        UUID key = UUID.nameUUIDFromBytes(("intent:" + intentId).getBytes(StandardCharsets.UTF_8));
        String fingerprint = "INTENT " + intentId;
        return switch (kind) {
            case "TRANSFER", "GOAL_TRANSFER" -> {
                AccountRef to = accounts.findById(uuid(p, "toAccountId")).orElseThrow(() -> ApiException.notFound("Account"));
                var dto = transfers.execute(userId, uuid(p, "fromAccountId"), new TransferService.Target(to, "toAccountId"),
                        new BigDecimal(p.get("amount").asString()), p.path("note").isNull() ? null : p.path("note").asString(null),
                        TransferSource.INTENT, null, true);
                yield new ResultRef("TRANSFER", dto.id());
            }
            case "ORDER" -> {
                var dto = orders.place(userId, key, fingerprint, new OrderRequest(uuid(p, "accountId"),
                        p.get("symbol").asString(), OrderSide.valueOf(p.get("side").asString()), p.get("quantity").asLong()))
                        .body();
                yield new ResultRef("ORDER", dto.id());
            }
            case "AUTOPILOT_RULE" -> {
                // Re-validated now: accounts, payees and symbols may have changed since the proposal.
                RuleDsl dsl = autopilot.requireValid(userId, p.get("rule"));
                yield new ResultRef("AUTOPILOT_RULE", autopilot.createApproved(userId, p.get("name").asString(), dsl).id());
            }
            case "FD_BOOKING" -> {
                var dto = deposits.book(userId, key, fingerprint, uuid(p, "fromAccountId"),
                        new BigDecimal(p.get("amount").asString()), p.get("tenureDays").asInt(),
                        OnMaturity.valueOf(p.get("onMaturity").asString())).body();
                yield new ResultRef("FIXED_DEPOSIT", dto.id());
            }
            default -> throw new IllegalStateException("Unsupported intent kind " + kind);
        };
    }

    // ------------------------------------------------------------------ kinds

    private Prepared transfer(UUID userId, JsonNode p) {
        AccountRef from = ownEveryday(userId, p, "fromAccountId");
        String handle = text(p, "toHandle", false);
        String number = text(p, "toAccountNumber", false);
        TransferService.Target target = transfers.resolveTarget(number, handle);
        AccountRef to = target.account();
        if (to.id().equals(from.id())) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + target.field(), "must be a different account");
        }
        BigDecimal amount = amount(p, "amount", new BigDecimal("1.00"), MAX_AMOUNT);
        String note = note(p);
        boolean own = to.userId().equals(userId);
        if (!own && !to.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + target.field(), "no account with this "
                    + (handle != null ? "handle" : "number"));
        }
        String name = own ? "your " + label(to) : HandleService.maskName(users.getUser(to.userId()).fullName());
        Boolean paidBefore = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM transfers WHERE user_id = ? "
                + "AND to_account_id = ? AND status = 'COMPLETED')", Boolean.class, userId, to.id());
        String risk = own ? "LOW" : amount.compareTo(HIGH) > 0 || !Boolean.TRUE.equals(paidBefore) ? "HIGH"
                : amount.compareTo(MEDIUM) > 0 ? "MEDIUM" : "LOW";
        List<Line> lines = new ArrayList<>(List.of(new Line("From", label(from)),
                new Line("To", name + (handle != null ? " · @" + HandleService.normalize(handle) : "") + " · "
                        + AccountNumbers.mask(to.accountNumber())),
                new Line("Amount", inr(amount))));
        if (!own && !Boolean.TRUE.equals(paidBefore)) {
            lines.add(new Line("Note", "You haven't paid this account before"));
        }
        if (note != null) {
            lines.add(new Line("Message", note));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromAccountId", from.id().toString());
        payload.put("toAccountId", to.id().toString());
        payload.put("amount", amount.toPlainString());
        payload.put("note", note);
        // External money always needs the human present; moving between your own accounts only when large.
        boolean stepUp = !own || amount.compareTo(STEP_UP_INTERNAL) > 0;
        return new Prepared(payload, new Summary((own ? "Move " : "Send ") + inr(amount) + " to " + name, lines), risk, stepUp);
    }

    private Prepared goalTransfer(UUID userId, JsonNode p) {
        AccountRef from = ownEveryday(userId, p, "fromAccountId");
        AccountRef to = own(userId, p, "toAccountId");
        if (to.type() != AccountType.GOAL) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.toAccountId", "must be one of your goals");
        }
        BigDecimal amount = amount(p, "amount", new BigDecimal("1.00"), MAX_AMOUNT);
        String goal = jdbc.queryForList("SELECT name FROM goals WHERE account_id = ?", String.class, to.id()).stream()
                .findFirst().orElse("goal");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromAccountId", from.id().toString());
        payload.put("toAccountId", to.id().toString());
        payload.put("amount", amount.toPlainString());
        payload.put("note", null);
        return new Prepared(payload, new Summary("Move " + inr(amount) + " to " + goal,
                List.of(new Line("From", label(from)), new Line("To", goal + " (goal)"), new Line("Amount", inr(amount)))),
                amount.compareTo(HIGH) > 0 ? "MEDIUM" : "LOW", amount.compareTo(STEP_UP_INTERNAL) > 0);
    }

    private Prepared order(UUID userId, JsonNode p) {
        AccountRef account = ownEveryday(userId, p, "accountId");
        String symbol = text(p, "symbol", true).toUpperCase(java.util.Locale.ROOT);
        String side = text(p, "side", true);
        if (!side.equals("BUY") && !side.equals("SELL")) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.side", "must be BUY or SELL");
        }
        if (p.has("orderType") && !"MARKET".equals(p.get("orderType").asString(""))) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.orderType", "only MARKET orders can be proposed");
        }
        JsonNode q = p.get("quantity");
        if (q == null || !q.isIntegralNumber() || q.asLong() < 1 || q.asLong() > 10_000) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.quantity", "must be a whole number 1–10000");
        }
        long quantity = q.asLong();
        List<Map<String, Object>> found = jdbc.queryForList("SELECT name, price FROM instruments WHERE symbol = ?", symbol);
        if (found.isEmpty()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.symbol", "unknown instrument");
        }
        BigDecimal price = (BigDecimal) found.getFirst().get("price");
        BigDecimal estimate = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_EVEN);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", account.id().toString());
        payload.put("symbol", symbol);
        payload.put("side", side);
        payload.put("quantity", quantity);
        String verb = side.equals("BUY") ? "Buy" : "Sell";
        return new Prepared(payload, new Summary(verb + " " + quantity + " × " + symbol, List.of(
                new Line("Instrument", found.getFirst().get("name") + " (" + symbol + ")"),
                new Line("Order", "Market, " + verb.toLowerCase(java.util.Locale.ROOT) + "s at the price when you approve"),
                new Line("Price now", inr(price.setScale(2, RoundingMode.HALF_EVEN)) + " per share"),
                new Line("Estimated total", inr(estimate)),
                new Line("Account", label(account)))),
                estimate.compareTo(HIGH) > 0 ? "HIGH" : estimate.compareTo(MEDIUM) > 0 ? "MEDIUM" : "LOW",
                estimate.compareTo(MEDIUM) > 0);
    }

    private Prepared autopilotRule(UUID userId, JsonNode p) {
        String name = text(p, "name", true);
        if (name.length() > 40) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.name", "must be 1–40 characters");
        }
        RuleDsl dsl = autopilot.requireValid(userId, p.get("rule"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("rule", json.valueToTree(dsl));
        // Activating a rule always needs the human present (F-AUTO-4).
        return new Prepared(payload, new Summary("Turn on Autopilot: " + name,
                List.of(new Line("Rule", autopilot.render(dsl)))), "MEDIUM", true);
    }

    private Prepared fdBooking(UUID userId, JsonNode p) {
        AccountRef from = ownEveryday(userId, p, "fromAccountId");
        BigDecimal amount = amount(p, "amount", new BigDecimal("1000.00"), MAX_AMOUNT);
        JsonNode d = p.get("tenureDays");
        if (d == null || !d.isIntegralNumber()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.tenureDays", "must be a whole number of days");
        }
        int days = d.asInt();
        BigDecimal rate = deposits.rates().stream().filter(r -> days >= r.minDays() && days <= r.maxDays())
                .map(FixedDepositService.RateDto::annualRate).findFirst()
                .orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.tenureDays", "no rate for this tenure"));
        String onMaturity = text(p, "onMaturity", true);
        if (!onMaturity.equals("PAYOUT") && !onMaturity.equals("RENEW")) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.onMaturity", "must be PAYOUT or RENEW");
        }
        BigDecimal interest = FixedDepositService.interestFor(amount, rate, days);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromAccountId", from.id().toString());
        payload.put("amount", amount.toPlainString());
        payload.put("tenureDays", days);
        payload.put("onMaturity", onMaturity);
        return new Prepared(payload, new Summary("Book a fixed deposit of " + inr(amount), List.of(
                new Line("From", label(from)), new Line("Tenure", days + " days"),
                new Line("Rate", rate.stripTrailingZeros().toPlainString() + "% p.a."),
                new Line("At maturity", inr(amount.add(interest)) + (onMaturity.equals("RENEW") ? ", renewed" : ", paid out")))),
                amount.compareTo(HIGH) > 0 ? "MEDIUM" : "LOW", true);
    }

    // ------------------------------------------------------------------ payload helpers

    private AccountRef own(UUID userId, JsonNode p, String field) {
        UUID id = uuid(p, field);
        // Same answer for "not yours" and "doesn't exist".
        return accounts.findById(id).filter(a -> a.userId().equals(userId))
                .orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "unknown account"));
    }

    private AccountRef ownEveryday(UUID userId, JsonNode p, String field) {
        AccountRef a = own(userId, p, field);
        if (!a.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "must be a savings or checking account");
        }
        return a;
    }

    private static UUID uuid(JsonNode p, String field) {
        try {
            return UUID.fromString(p.path(field).asString(""));
        } catch (IllegalArgumentException e) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "must be an id");
        }
    }

    private static String text(JsonNode p, String field, boolean required) {
        JsonNode n = p.get(field);
        if (n == null || n.isNull() || (n.isString() && n.asString().isBlank())) {
            if (required) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "is required");
            }
            return null;
        }
        if (!n.isString() || n.asString().length() > 60) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "must be text up to 60 characters");
        }
        return n.asString().strip();
    }

    private static String note(JsonNode p) {
        JsonNode n = p.get("note");
        if (n == null || n.isNull()) {
            return null;
        }
        String s = n.isString() ? n.asString().strip() : "";
        if (s.length() > 140 || !s.matches(TextRules.NO_CONTROL_CHARS)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.note", "must be up to 140 characters");
        }
        return s.isEmpty() ? null : s;
    }

    /** A JSON string with at most 2 decimals, within [min, max] (money is never a JSON number). */
    private static BigDecimal amount(JsonNode p, String field, BigDecimal min, BigDecimal max) {
        JsonNode n = p.get(field);
        BigDecimal v;
        try {
            v = n != null && n.isString() ? new BigDecimal(n.asString()) : null;
        } catch (NumberFormatException e) {
            v = null;
        }
        if (v == null || v.scale() > 2 || v.compareTo(min) < 0 || v.compareTo(max) > 0) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field,
                    "must be a string amount from " + min.toPlainString() + " to " + max.toPlainString());
        }
        return v.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String label(AccountRef a) {
        String type = a.type().name().charAt(0) + a.type().name().substring(1).toLowerCase(java.util.Locale.ROOT)
                .replace('_', ' ');
        return type + " " + AccountNumbers.mask(a.accountNumber());
    }

    /** ₹1,23,456.78 (Indian digit grouping). */
    static String inr(BigDecimal amount) {
        return Inr.format(amount);
    }
}
