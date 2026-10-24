package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.autopilot.RuleDsl.Action;
import com.quantgen.autopilot.RuleDsl.Amount;
import com.quantgen.autopilot.RuleDsl.Trigger;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Inr;
import com.quantgen.insights.CategoryService;

/**
 * F-AUTO-2: the plain-language sentence of a rule, generated from the validated DSL by the server, so the user
 * reads what will actually run (D-024), never what a model wrote. Same wording as the frontend's mock renderer.
 */
@Component
class RuleRenderer {

    private static final Map<String, String> WEEKDAYS = Map.of("MON", "Monday", "TUE", "Tuesday", "WED", "Wednesday",
            "THU", "Thursday", "FRI", "Friday", "SAT", "Saturday", "SUN", "Sunday");

    private final AccountService accounts;
    private final JdbcTemplate jdbc;

    RuleRenderer(AccountService accounts, JdbcTemplate jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    String render(RuleDsl r) {
        Trigger t = r.trigger();
        String when = switch (t.type()) {
            case "MONEY_RECEIVED" -> "When " + (t.minAmount() != null ? "at least " + inr(t.minAmount()) : "money")
                    + (t.counterpartyContains() != null ? " from “" + t.counterpartyContains() + "”" : "")
                    + " lands in " + account(t.accountId());
            case "SCHEDULE" -> "MONTHLY".equals(t.frequency())
                    ? "On the " + ordinal(t.dayOfMonth()) + " of every month at 08:00 IST"
                    : "Every " + WEEKDAYS.get(t.weekday()) + " at 08:00 IST";
            case "BALANCE_ABOVE" -> "Every day at 08:00 IST, if " + account(t.accountId()) + " holds more than "
                    + inr(t.threshold());
            case "BUDGET_THRESHOLD" -> "When your " + CategoryService.label(t.category()).toLowerCase(Locale.ROOT)
                    + " spending reaches " + t.percent() + "% of its monthly budget (once a month)";
            default -> "When " + t.symbol() + " goes " + ("ABOVE".equals(t.direction()) ? "above " : "below ")
                    + inr(t.price());
        };
        StringBuilder s = new StringBuilder(when);
        r.conditions().forEach(c -> s.append(", if ").append(account(c.accountId())).append(" holds at least ")
                .append(inr(c.amount())));
        List<String> acts = new ArrayList<>();
        r.actions().forEach(a -> acts.add(action(a)));
        String list = acts.size() > 1 ? String.join(", ", acts.subList(0, acts.size() - 1)) + ", then " + acts.getLast()
                : acts.getFirst();
        return s + ": " + list + ". At most " + inr(r.caps().perRun()) + " per run and " + inr(r.caps().perMonth())
                + " a month.";
    }

    String action(Action a) {
        return switch (a.type()) {
            case "TRANSFER" -> "move " + amount(a.amount()) + " from " + account(a.fromAccountId()) + " to "
                    + (a.toBeneficiaryId() != null ? payee(a.toBeneficiaryId()) : account(a.toAccountId()));
            case "BUY" -> "buy " + amount(a.amount()) + " of " + a.symbol() + " (whole shares)";
            case "ROUND_UP_SWEEP" -> "move the round-ups to the next ₹" + a.roundTo()
                    + " on spends from " + account(a.fromAccountId()) + " since the previous scheduled day to "
                    + account(a.toAccountId());
            default -> "notify me: “" + a.message() + "”";
        };
    }

    /** "Savings ••••1234", or the goal's name for a goal account. */
    String account(UUID id) {
        if (id == null) {
            return "an account";
        }
        List<String> goal = jdbc.queryForList("SELECT name FROM goals WHERE account_id = ?", String.class, id);
        if (!goal.isEmpty()) {
            return goal.getFirst();
        }
        return accounts.findById(id).map(RuleRenderer::label).orElse("an account");
    }

    private String payee(UUID id) {
        return jdbc.queryForList("SELECT nickname FROM beneficiaries WHERE id = ?", String.class, id).stream()
                .findFirst().orElse("a saved payee");
    }

    static String label(AccountRef a) {
        String type = a.type().name().charAt(0) + a.type().name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        return type + " " + AccountNumbers.mask(a.accountNumber());
    }

    static String amount(Amount a) {
        return switch (a.mode()) {
            case "FIXED" -> inr(a.value());
            case "PERCENT_OF_TRIGGER" -> a.value().stripTrailingZeros().toPlainString() + "% of it";
            default -> "everything above " + inr(a.value());
        };
    }

    static String ordinal(int n) {
        String suffix = n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    static String inr(BigDecimal amount) {
        return Inr.format(amount);
    }
}
