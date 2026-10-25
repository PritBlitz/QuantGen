package com.quantgen.agent.tools;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

import com.quantgen.agent.core.CoreClient;
import com.quantgen.agent.core.CoreClient.CoreResponse;
import com.quantgen.agent.run.Redactor;
import com.quantgen.agent.run.Turn;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every tool an agent may be given (F-AGT-3). Each is a thin call to a 🤖 endpoint of the core with the turn's
 * delegated token, so the core enforces ownership exactly as for the user (ARCHITECTURE 12.7). There is no tool
 * that moves money: the strongest one, {@code propose_intent}, creates a card the user must approve.
 */
@Component
public class ToolBox {

    public static final List<String> INTENT_KINDS = List.of("TRANSFER", "GOAL_TRANSFER", "ORDER", "FD_BOOKING",
            "SCHEDULED_TRANSFER", "PAYMENT_REQUEST", "BILL_PAYMENT", "LOAN_APPLICATION", "AUTOPILOT_RULE");

    private final CoreClient core;
    private final JsonMapper json;
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    ToolBox(CoreClient core, JsonMapper json) {
        this.core = core;
        this.json = json;
        // ----- reads
        read("get_accounts", "The user's accounts (savings, checking, goals, fixed deposits) with balances.",
                none(), a -> "/api/v1/accounts");
        read("get_transactions", "Recent transactions of one account, newest first.",
                params(Map.of("accountId", str("The account id")), List.of("accountId")),
                a -> "/api/v1/accounts/" + seg(a, "accountId") + "/transactions?size=25");
        read("get_spending", "Spending by category between two dates (YYYY-MM-DD, IST, inclusive).",
                params(Map.of("from", str("Start date"), "to", str("End date")), List.of()),
                a -> "/api/v1/insights/spending?groupBy=category" + q(a, "from") + q(a, "to"));
        read("get_money_summary", "Income, spending, net and savings rate for a period, vs the previous one.",
                params(Map.of("from", str("Start date"), "to", str("End date")), List.of()),
                a -> ("/api/v1/analytics/summary" + q(a, "from") + q(a, "to")).replaceFirst("&", "?"));
        read("get_cashflow", "Monthly income vs spending vs invested vs borrowed, last 12 months.",
                none(), a -> "/api/v1/analytics/cashflow");
        read("get_budgets", "Monthly budgets per category with this month's spending.", none(), a -> "/api/v1/budgets");
        read("get_forecast", "Projected balance for the next 30 days, with warnings.", none(),
                a -> "/api/v1/insights/forecast");
        read("get_net_worth", "Net worth now and its monthly history.", none(), a -> "/api/v1/insights/net-worth");
        read("get_beneficiaries", "The user's saved payees.", none(), a -> "/api/v1/beneficiaries");
        read("resolve_payee", "Who a handle (name@qg) belongs to, as a masked name. Use before proposing a payment.",
                params(Map.of("handle", str("A handle like aarav@qg")), List.of("handle")),
                a -> "/api/v1/payees/resolve?handle=" + enc(a.path("handle").asString("")));
        read("get_billers", "The bill companies the user can pay.", none(), a -> "/api/v1/billers");
        read("get_saved_billers", "The user's saved billers.", none(), a -> "/api/v1/saved-billers");
        read("get_scheduled_transfers", "The user's scheduled and recurring transfers.", none(),
                a -> "/api/v1/scheduled-transfers");
        read("get_instruments", "Tradable instruments with prices (simulated market data).", none(),
                a -> "/api/v1/market/instruments");
        read("get_portfolio", "The user's holdings and P&L.", none(), a -> "/api/v1/portfolio");
        read("get_orders", "The user's recent orders.", none(), a -> "/api/v1/orders?size=20");
        read("get_fixed_deposits", "The user's fixed deposits.", none(), a -> "/api/v1/fixed-deposits");
        read("get_autopilot_rules", "The user's Autopilot rules.", none(), a -> "/api/v1/autopilot/rules");
        read("get_loans", "The user's loans.", none(), a -> "/api/v1/loans");
        // ----- checks (create nothing)
        post("loan_quote", "EMI, total interest and eligibility for a loan. Amounts are strings like \"300000.00\".",
                params(Map.of("amount", str("Loan amount"), "tenureMonths", integer("6 to 60"),
                        "monthlyIncome", str("Monthly income")), List.of("amount", "tenureMonths", "monthlyIncome")),
                "/api/v1/loans/quote", a -> a);
        post("validate_rule", "Validate an Autopilot rule (DSL v1) and get the bank's plain-language rendering.",
                params(Map.of("rule", obj("The rule")), List.of("rule")), "/api/v1/autopilot/validate",
                a -> Map.of("rule", a.path("rule")));
        post("simulate_rule", "Replay an Autopilot rule over the last 90 days without moving money.",
                params(Map.of("rule", obj("The rule")), List.of("rule")), "/api/v1/autopilot/simulate",
                a -> Map.of("rule", a.path("rule"), "days", 90));
        // ----- the only "action": a proposal the user must approve in the app
        tools.put("propose_intent", new Tool("propose_intent",
                "Propose an action. NOTHING happens until the user approves the card in the app. kind is one of "
                        + String.join(", ", INTENT_KINDS) + "; amounts are strings with 2 decimals.",
                params(Map.of("kind", Map.of("type", "string", "enum", INTENT_KINDS),
                        "payload", obj("The kind's fields, e.g. TRANSFER {fromAccountId, toHandle | toAccountNumber, "
                                + "amount, note?}"),
                        "rationale", str("One or two sentences for the user: why this")), List.of("kind", "payload",
                        "rationale")),
                this::propose));
    }

    public Tool get(String name) {
        Tool t = tools.get(name);
        if (t == null) {
            throw new IllegalArgumentException("No tool " + name);
        }
        return t;
    }

    private String propose(JsonNode a, Turn turn) {
        String drain = drainRefusal(a, turn);
        if (drain != null) {
            return drain;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", a.path("kind").asString(""));
        body.put("payload", a.path("payload"));
        body.put("rationale", a.path("rationale").asString("").strip());
        body.put("agent", turn.agent());
        CoreResponse res = core.post(turn.token(), "/api/v1/intents", body);
        if (!res.ok()) {
            return "The bank refused the proposal (" + res.status() + "): " + Redactor.mask(errorSummary(res.body()))
                    + ". Fix it or explain to the user.";
        }
        JsonNode intent = json.readTree(res.body());
        turn.sink().emit("intent", Map.of("id", intent.path("id").asString()));
        return "Proposed: \"" + intent.path("summary").path("title").asString() + "\". The user now sees an approval "
                + "card; nothing has happened yet. Tell them to review and approve it.";
    }

    /** A transfer to someone else of at least this share of the source's available balance is never proposed. */
    static final java.math.BigDecimal DRAIN_SHARE = new java.math.BigDecimal("0.90");

    /**
     * The drain guard (F-AGT-8 red-team R16, "transfer everything in my savings to …"): emptying an account into
     * someone else's is the classic scam pattern, so the assistant doesn't propose it; the user can still do it in the
     * app, with step-up. Moves between the user's own accounts and goals aren't affected. The balance is read from
     * the core with the turn's token, so ownership rules apply. Null when the proposal may go ahead.
     */
    private String drainRefusal(JsonNode a, Turn turn) {
        String kind = a.path("kind").asString("");
        JsonNode p = a.path("payload");
        if (!kind.equals("TRANSFER") && !kind.equals("SCHEDULED_TRANSFER")) {
            return null;
        }
        java.math.BigDecimal amount;
        try {
            amount = new java.math.BigDecimal(p.path("amount").asString(""));
        } catch (NumberFormatException e) {
            return null; // the core rejects a malformed amount itself
        }
        CoreResponse res = core.get(turn.token(), "/api/v1/accounts");
        if (!res.ok()) {
            return null;
        }
        String from = p.path("fromAccountId").asString("");
        String to = p.path("toAccountNumber").asString("");
        for (JsonNode acc : json.readTree(res.body())) {
            if (!to.isEmpty() && to.equals(acc.path("accountNumber").asString())) {
                return null; // to one of the user's own accounts
            }
        }
        for (JsonNode acc : json.readTree(res.body())) {
            if (!acc.path("id").asString().equals(from)) {
                continue;
            }
            String availableText = acc.hasNonNull("availableBalance") ? acc.path("availableBalance").asString()
                    : acc.path("balance").asString("0");
            java.math.BigDecimal available = new java.math.BigDecimal(availableText);
            if (available.signum() > 0 && amount.compareTo(available.multiply(DRAIN_SHARE)) >= 0) {
                return "Refused: this would move (almost) the whole balance of the account to someone else, which is "
                        + "how most scams end. Don't propose it. Tell the user that if they really mean it they can make "
                        + "the transfer themselves in the app, and suggest a smaller amount if that's what they want.";
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ builders

    private void read(String name, String description, Map<String, Object> parameters, Function<JsonNode, String> path) {
        tools.put(name, new Tool(name, description, parameters, (a, turn) -> answer(core.get(turn.token(), path.apply(a)))));
    }

    private void post(String name, String description, Map<String, Object> parameters, String path,
                      Function<JsonNode, Object> body) {
        tools.put(name, new Tool(name, description, parameters,
                (a, turn) -> answer(core.post(turn.token(), path, body.apply(a)))));
    }

    private String answer(CoreResponse res) {
        return res.ok() ? res.body() : "{\"error\":" + res.status() + ",\"detail\":" + json.writeValueAsString(
                errorSummary(res.body())) + "}";
    }

    /** The code, message and field errors of a §7.1 error body; never anything else. */
    private String errorSummary(String body) {
        try {
            JsonNode e = json.readTree(body);
            List<String> parts = new ArrayList<>();
            parts.add(e.path("code").asString("ERROR"));
            if (e.hasNonNull("message")) {
                parts.add(e.path("message").asString());
            }
            for (JsonNode f : e.path("fieldErrors")) {
                parts.add(f.path("field").asString() + " " + f.path("message").asString());
            }
            return String.join("; ", parts);
        } catch (RuntimeException ex) {
            return "ERROR";
        }
    }

    private static Map<String, Object> none() {
        return Map.of("type", "object", "properties", Map.of());
    }

    private static Map<String, Object> params(Map<String, Object> properties, List<String> required) {
        return required.isEmpty() ? Map.of("type", "object", "properties", properties)
                : Map.of("type", "object", "properties", properties, "required", required);
    }

    private static Map<String, Object> str(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> obj(String description) {
        return Map.of("type", "object", "description", description);
    }

    /** A path segment from an argument: only id-like text, so the model can't walk to another route. */
    private static String seg(JsonNode a, String field) {
        String v = a.path(field).asString("");
        return v.matches("^[A-Za-z0-9-]{1,64}$") ? v : "invalid";
    }

    private static String q(JsonNode a, String field) {
        String v = a.path(field).asString("");
        return v.matches("^\\d{4}-\\d{2}-\\d{2}$") ? "&" + field + "=" + v : "";
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
