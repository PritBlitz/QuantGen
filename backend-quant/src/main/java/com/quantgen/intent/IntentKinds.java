package com.quantgen.intent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.quantgen.common.error.ApiError;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Inr;
import com.quantgen.common.web.TextRules;
import com.quantgen.intent.IntentService.Line;
import com.quantgen.intent.IntentService.ResultRef;
import com.quantgen.intent.IntentService.Summary;
import com.quantgen.loan.api.LoanDtos.ApplyRequest;
import com.quantgen.loan.api.LoanDtos.QuoteDto;
import com.quantgen.loan.api.LoanDtos.QuoteRequest;
import com.quantgen.loan.service.LoanService;
import com.quantgen.payment.domain.TransferSource;
import com.quantgen.payment.service.BillService;
import com.quantgen.payment.service.HandleService;
import com.quantgen.payment.service.PaymentRequestService;
import com.quantgen.payment.service.ScheduledTransferService;
import com.quantgen.payment.service.ScheduledTransferService.CreateCommand;
import com.quantgen.payment.service.ScheduledTransferService.Frequency;
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

    static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000.00");
    /** Same bound as the manual endpoints for schedules and requests. */
    static final BigDecimal MAX_PAYMENT = new BigDecimal("200000.00");
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);
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
    private final ScheduledTransferService scheduled;
    private final PaymentRequestService requests;
    private final HandleService handles;
    private final BillService bills;
    private final LoanService loans;
    private final JsonMapper json;

    IntentKinds(JdbcTemplate jdbc, AccountService accounts, UserService users, TransferService transfers,
                OrderService orders, FixedDepositService deposits, AutopilotService autopilot,
                ScheduledTransferService scheduled, PaymentRequestService requests, HandleService handles,
                BillService bills, LoanService loans, JsonMapper json) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.users = users;
        this.transfers = transfers;
        this.orders = orders;
        this.deposits = deposits;
        this.autopilot = autopilot;
        this.scheduled = scheduled;
        this.requests = requests;
        this.handles = handles;
        this.bills = bills;
        this.loans = loans;
        this.json = json;
    }

    Prepared prepare(UUID userId, String kind, JsonNode p) {
        return switch (kind == null ? "" : kind) {
            case "TRANSFER" -> transfer(userId, p);
            case "GOAL_TRANSFER" -> goalTransfer(userId, p);
            case "ORDER" -> order(userId, p);
            case "FD_BOOKING" -> fdBooking(userId, p);
            case "AUTOPILOT_RULE" -> autopilotRule(userId, p);
            case "SCHEDULED_TRANSFER" -> scheduledTransfer(userId, p);
            case "PAYMENT_REQUEST" -> paymentRequest(userId, p);
            case "BILL_PAYMENT" -> billPayment(userId, p);
            case "LOAN_APPLICATION" -> loanApplication(userId, p);
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
            case "SCHEDULED_TRANSFER" -> {
                // Step-up (when the card asked for it) happened at approval, so the instruction is pre-authorized.
                var dto = scheduled.create(userId, key, fingerprint, scheduleCommand(p), null, true).body();
                yield new ResultRef("SCHEDULED_TRANSFER", dto.id());
            }
            case "PAYMENT_REQUEST" -> {
                var dto = requests.createInternal(userId, p.get("toHandle").asString(),
                        new BigDecimal(p.get("amount").asString()), p.path("note").asString(null), null);
                yield new ResultRef("PAYMENT_REQUEST", dto.id());
            }
            case "BILL_PAYMENT" -> {
                var dto = bills.pay(userId, key, fingerprint, uuid(p, "billId"), uuid(p, "fromAccountId")).body();
                yield new ResultRef("BILL_PAYMENT", dto.id());
            }
            case "LOAN_APPLICATION" -> {
                var dto = loans.apply(userId, new ApplyRequest(new BigDecimal(p.get("amount").asString()),
                        p.get("tenureMonths").asInt(), new BigDecimal(p.get("monthlyIncome").asString()),
                        p.get("purpose").asString(), uuid(p, "disburseAccountId")));
                yield new ResultRef("LOAN", dto.id());
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

    private Prepared scheduledTransfer(UUID userId, JsonNode p) {
        AccountRef from = ownEveryday(userId, p, "fromAccountId");
        String handle = text(p, "toHandle", false);
        String number = text(p, "toAccountNumber", false);
        BigDecimal amount = amount(p, "amount", new BigDecimal("1.00"), MAX_PAYMENT);
        String note = note(p);
        LocalDate start = date(p, "startDate", true);
        String freq = text(p, "frequency", true);
        if (!List.of("ONCE", "WEEKLY", "MONTHLY").contains(freq)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.frequency", "must be ONCE, WEEKLY or MONTHLY");
        }
        LocalDate end = date(p, "endDate", false);
        Integer count = null;
        JsonNode c = p.get("count");
        if (c != null && !c.isNull()) {
            if (!c.isIntegralNumber() || c.asLong() < 1 || c.asLong() > ScheduledTransferService.MAX_COUNT) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.count",
                        "must be a whole number 1–" + ScheduledTransferService.MAX_COUNT);
            }
            count = c.asInt();
        }
        CreateCommand cmd = new CreateCommand(from.id(), number, handle, amount, note, start, Frequency.valueOf(freq),
                end, count);
        ScheduledTransferService.Checked checked = inPayload(() -> scheduled.check(userId, cmd));
        AccountRef to = checked.to();
        boolean own = to.userId().equals(userId);
        String name = own ? "your " + label(to) : HandleService.maskName(users.getUser(to.userId()).fullName());
        String repeats = switch (cmd.frequency()) {
            case ONCE -> "Once";
            case WEEKLY -> "Every " + start.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
            case MONTHLY -> "Every month on day " + start.getDayOfMonth();
        };
        List<Line> lines = new ArrayList<>(List.of(new Line("From", label(from)),
                new Line("To", name + (handle != null ? " · @" + HandleService.normalize(handle) : "") + " · "
                        + AccountNumbers.mask(to.accountNumber())),
                new Line("Amount", inr(checked.amount()) + (cmd.frequency() == Frequency.ONCE ? "" : " each time")),
                new Line(cmd.frequency() == Frequency.ONCE ? "On" : "Starts", start.format(DATE)),
                new Line("Repeats", repeats)));
        if (cmd.frequency() != Frequency.ONCE) {
            lines.add(new Line("Ends", count != null ? "After " + count + " payment" + (count == 1 ? "" : "s")
                    : end != null ? "On " + end.format(DATE) : "When you cancel it"));
        }
        if (checked.note() != null) {
            lines.add(new Line("Message", checked.note()));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromAccountId", from.id().toString());
        // The resolved number, not the handle: a later handle change can't redirect what was approved.
        payload.put("toAccountNumber", to.accountNumber());
        payload.put("amount", checked.amount().toPlainString());
        payload.put("note", checked.note());
        payload.put("startDate", start.toString());
        payload.put("frequency", freq);
        payload.put("endDate", end == null ? null : end.toString());
        payload.put("count", count);
        String risk = own ? "LOW" : checked.amount().compareTo(HIGH) > 0 ? "HIGH" : "MEDIUM";
        String title = (cmd.frequency() == Frequency.ONCE ? "Schedule " : "Set up a repeating payment of ")
                + inr(checked.amount()) + " to " + name;
        // Runs happen with nobody present, so money to someone else is confirmed with step-up now.
        return new Prepared(payload, new Summary(title, lines), risk,
                !own || checked.amount().compareTo(STEP_UP_INTERNAL) > 0);
    }

    private static CreateCommand scheduleCommand(JsonNode p) {
        return new CreateCommand(uuid(p, "fromAccountId"), p.get("toAccountNumber").asString(), null,
                new BigDecimal(p.get("amount").asString()), p.path("note").asString(null),
                LocalDate.parse(p.get("startDate").asString()), Frequency.valueOf(p.get("frequency").asString()),
                p.path("endDate").isString() ? LocalDate.parse(p.get("endDate").asString()) : null,
                p.path("count").isIntegralNumber() ? Integer.valueOf(p.get("count").asInt()) : null);
    }

    private Prepared paymentRequest(UUID userId, JsonNode p) {
        String raw = text(p, "toHandle", true);
        UUID payer = handles.userFor(raw).orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST,
                "payload.toHandle", "no account with this handle"));
        if (payer.equals(userId)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.toHandle", "you can't request money from yourself");
        }
        if (handles.handleOf(userId) == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Set your handle before requesting money");
        }
        BigDecimal amount = amount(p, "amount", new BigDecimal("1.00"), MAX_PAYMENT);
        String note = note(p);
        String handle = handles.handleOf(payer);
        String name = HandleService.maskName(users.getUser(payer).fullName());
        List<Line> lines = new ArrayList<>(List.of(new Line("From", name + " · @" + handle),
                new Line("Amount", inr(amount))));
        if (note != null) {
            lines.add(new Line("Message", note));
        }
        lines.add(new Line("Note", "No money moves until they choose to pay"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("toHandle", handle);
        payload.put("amount", amount.toPlainString());
        payload.put("note", note);
        return new Prepared(payload, new Summary("Ask " + name + " for " + inr(amount), lines), "LOW", false);
    }

    private Prepared billPayment(UUID userId, JsonNode p) {
        AccountRef from = ownEveryday(userId, p, "fromAccountId");
        UUID billerId = uuid(p, "billerId");
        BillService.BillerDto biller = bills.billers(null).stream().filter(b -> b.id().equals(billerId)).findFirst()
                .orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.billerId", "unknown biller"));
        String consumer = text(p, "consumerNumber", true);
        if (!consumer.matches(biller.consumerNumberPattern())) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.consumerNumber",
                    "is not a valid " + biller.consumerNumberLabel().toLowerCase(Locale.ROOT));
        }
        // Fetching pins the amount: the bill row is what gets paid, so the card and the payment can't differ.
        BillService.BillDto bill = inPayload(() -> bills.fetch(userId, billerId, consumer));
        BigDecimal amount = bill.amount().setScale(2, RoundingMode.UNNECESSARY);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromAccountId", from.id().toString());
        payload.put("billId", bill.billId().toString());
        payload.put("billerId", billerId.toString());
        payload.put("amount", amount.toPlainString());
        return new Prepared(payload, new Summary("Pay " + inr(amount) + " to " + biller.name(), List.of(
                new Line("From", label(from)),
                new Line("Biller", biller.name() + " (" + biller.category().toLowerCase(Locale.ROOT).replace('_', ' ') + ")"),
                new Line(biller.consumerNumberLabel(), AccountNumbers.mask(consumer)),
                new Line("Amount", inr(amount)),
                new Line("Due", bill.dueDate().format(DATE)))),
                amount.compareTo(HIGH) > 0 ? "HIGH" : amount.compareTo(MEDIUM) > 0 ? "MEDIUM" : "LOW", true);
    }

    private Prepared loanApplication(UUID userId, JsonNode p) {
        AccountRef to = ownEveryday(userId, p, "disburseAccountId");
        BigDecimal amount = amount(p, "amount", new BigDecimal("10000.00"), MAX_AMOUNT);
        BigDecimal income = amount(p, "monthlyIncome", new BigDecimal("1.00"), new BigDecimal("100000000.00"));
        JsonNode t = p.get("tenureMonths");
        if (t == null || !t.isIntegralNumber() || t.asInt() < 6 || t.asInt() > 60) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.tenureMonths", "must be a whole number 6–60");
        }
        int months = t.asInt();
        JsonNode pn = p.get("purpose");
        String purpose = pn != null && pn.isString() ? pn.asString().strip() : "";
        if (purpose.length() < 3 || purpose.length() > 200 || !purpose.matches(TextRules.NO_CONTROL_CHARS)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload.purpose", "must be 3–200 characters");
        }
        QuoteDto quote = loans.quote(userId, new QuoteRequest(amount, months, income));
        if (!quote.eligible()) {
            // Said now, so the assistant can explain it instead of showing a card that can only fail.
            throw new ApiException(ErrorCode.LOAN_NOT_ELIGIBLE, quote.reason());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("amount", amount.toPlainString());
        payload.put("tenureMonths", months);
        payload.put("monthlyIncome", income.toPlainString());
        payload.put("purpose", purpose);
        payload.put("disburseAccountId", to.id().toString());
        return new Prepared(payload, new Summary("Apply for a loan of " + inr(amount), List.of(
                new Line("Amount", inr(amount)),
                new Line("Tenure", months + " months"),
                new Line("EMI", inr(quote.emi()) + " a month"),
                new Line("Interest", LoanService.ANNUAL_RATE.stripTrailingZeros().toPlainString() + "% p.a., "
                        + inr(quote.totalInterest()) + " in total"),
                new Line("Monthly income (declared)", inr(income)),
                new Line("Paid into", label(to)),
                new Line("Purpose", purpose),
                new Line("Note", "An admin reviews every application; nothing is paid out until it's approved"))),
                "MEDIUM", true);
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

    /** Runs a service-side validation and points its field errors into the payload ({@code payload.<field>}). */
    private static <T> T inPayload(java.util.function.Supplier<T> check) {
        try {
            return check.get();
        } catch (ApiException e) {
            if (e.code() != ErrorCode.VALIDATION_FAILED || e.fieldErrors().isEmpty()) {
                throw e;
            }
            ApiError.FieldError f = e.fieldErrors().getFirst();
            throw ApiException.fieldError(e.status(), "payload." + f.field(), f.message());
        }
    }

    private static LocalDate date(JsonNode p, String field, boolean required) {
        JsonNode n = p.get(field);
        if (n == null || n.isNull()) {
            if (required) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "is required");
            }
            return null;
        }
        try {
            return LocalDate.parse(n.isString() ? n.asString() : "");
        } catch (DateTimeParseException e) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload." + field, "must be a date YYYY-MM-DD");
        }
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
