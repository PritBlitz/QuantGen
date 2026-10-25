package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M2 part 4: split groups (F-PAY-12). */
class SplitGroupsIT extends IntegrationTest {

    private record Person(TestUsers.Session session, UUID account, String handle) {
    }

    private Person person() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        String handle = HandlesAndRequestsIT.unique("p");
        Response res = put("/api/v1/users/me/handle", Map.of("handle", handle, "accountId", acc.toString()),
                s.accessToken());
        assertThat(res.status()).isEqualTo(200);
        return new Person(s, acc, handle);
    }

    @Test
    void groupCreationIsValidatedAndPrivateToMembers() {
        Person a = person();
        Person b = person();
        TestUsers.Session noHandle = TestUsers.registerAndLogin(this);
        assertThat(postJson("/api/v1/split-groups", Map.of("name", "Trip", "memberHandles", List.of(a.handle())),
                Bank.auth(noHandle)).status()).isEqualTo(409);
        assertThat(createGroup(a, "Trip", List.of(HandlesAndRequestsIT.unique("ghost"))).status()).isEqualTo(400);
        assertThat(createGroup(a, "Trip", List.of(a.handle())).status()).isEqualTo(400); // only yourself
        assertThat(createGroup(a, "", List.of(b.handle())).status()).isEqualTo(400);

        Response ok = createGroup(a, "Goa trip", List.of(b.handle(), b.handle().toUpperCase()));
        assertThat(ok.status()).isEqualTo(201);
        JsonNode g = ok.jsonBody();
        assertThat(g.get("members").size()).isEqualTo(2);
        assertThat(g.get("members").get(0).get("handle").asString()).isEqualTo(a.handle());
        assertThat(g.get("members").get(0).get("displayName").asString()).isEqualTo("Test U.");
        assertThat(g.get("members").get(0).get("balance").asString()).isEqualTo("0.00");
        String id = g.get("id").asString();

        assertThat(get("/api/v1/split-groups/" + id, b.session().accessToken()).status()).isEqualTo(200);
        assertThat(get("/api/v1/split-groups", b.session().accessToken()).jsonBody().size()).isEqualTo(1);
        Person outsider = person();
        assertThat(get("/api/v1/split-groups/" + id, outsider.session().accessToken()).status()).isEqualTo(404);
        assertThat(addExpense(outsider, id, outsider.handle(), "10.00", Map.of("type", "EQUAL")).status())
                .isEqualTo(404);
    }

    @Test
    void sharesAreComputedOnTheServerWithLeftoverPaisaToThePayer() {
        Person a = person();
        Person b = person();
        Person c = person();
        String id = createGroup(a, "Flat", List.of(b.handle(), c.handle())).jsonBody().get("id").asString();

        // 100.00 / 3 = 33.33 each, the leftover 0.01 goes to the payer (b).
        JsonNode equal = addExpense(a, id, b.handle(), "100.00", Map.of("type", "EQUAL")).jsonBody();
        assertThat(shareOf(equal, a.handle())).isEqualTo("33.33");
        assertThat(shareOf(equal, b.handle())).isEqualTo("33.34");
        assertThat(shareOf(equal, c.handle())).isEqualTo("33.33");

        JsonNode exact = addExpense(a, id, a.handle(), "50.00", Map.of("type", "EXACT", "shares", List.of(
                Map.of("handle", b.handle(), "value", "20.00"), Map.of("handle", c.handle(), "value", "30.00"))))
                .jsonBody();
        assertThat(shareOf(exact, b.handle())).isEqualTo("20.00");
        assertThat(exact.get("shares").size()).isEqualTo(2);

        // 10.00 split 33.33 / 33.33 / 33.34 percent → 3.33 / 3.33 / 3.33, leftover 0.01 to the payer (c).
        JsonNode pct = addExpense(a, id, c.handle(), "10.00", Map.of("type", "PERCENT", "shares", List.of(
                Map.of("handle", a.handle(), "value", "33.33"), Map.of("handle", b.handle(), "value", "33.33"),
                Map.of("handle", c.handle(), "value", "33.34")))).jsonBody();
        assertThat(shareOf(pct, c.handle())).isEqualTo("3.34");

        // Bad splits.
        assertThat(addExpense(a, id, a.handle(), "50.00", Map.of("type", "EXACT", "shares", List.of(
                Map.of("handle", b.handle(), "value", "20.00")))).status()).isEqualTo(400);
        assertThat(addExpense(a, id, a.handle(), "50.00", Map.of("type", "PERCENT", "shares", List.of(
                Map.of("handle", b.handle(), "value", "60"), Map.of("handle", c.handle(), "value", "30")))).status())
                .isEqualTo(400);
        assertThat(addExpense(a, id, a.handle(), "50.00", Map.of("type", "EXACT", "shares", List.of(
                Map.of("handle", b.handle(), "value", "25.00"), Map.of("handle", b.handle(), "value", "25.00"))))
                .status()).isEqualTo(400);
        assertThat(addExpense(a, id, HandlesAndRequestsIT.unique("ghost"), "50.00", Map.of("type", "EQUAL")).status())
                .isEqualTo(400);

        // Balances: a paid 50, owes 33.33 + 3.33 → +13.34; b paid 100, owes 33.34 + 20 + 3.33 → +43.33;
        // c paid 10, owes 33.33 + 30 + 3.34 → −56.67. They sum to zero.
        JsonNode group = get("/api/v1/split-groups/" + id, a.session().accessToken()).jsonBody();
        assertThat(balanceOf(group, a.handle())).isEqualByComparingTo("13.34");
        assertThat(balanceOf(group, b.handle())).isEqualByComparingTo("43.33");
        assertThat(balanceOf(group, c.handle())).isEqualByComparingTo("-56.67");
        assertThat(group.get("expenses").get(0).get("splitType").asString()).isEqualTo("PERCENT");
    }

    @Test
    void settleUpCreatesRequestsOnceAndPayingSettlesTheGroup() {
        Person a = person();
        Person b = person();
        String id = createGroup(a, "Dinner", List.of(b.handle())).jsonBody().get("id").asString();
        addExpense(a, id, a.handle(), "300.00", Map.of("type", "EQUAL"));

        // b owes nothing to collect, so b's settle-up creates nothing.
        assertThat(postJson("/api/v1/split-groups/" + id + "/settle", Map.of(), Bank.auth(b.session())).jsonBody()
                .size()).isZero();

        JsonNode created = postJson("/api/v1/split-groups/" + id + "/settle", Map.of(), Bank.auth(a.session()))
                .jsonBody();
        assertThat(created.size()).isEqualTo(1);
        JsonNode req = created.get(0);
        assertThat(req.get("toHandle").asString()).isEqualTo(b.handle());
        assertThat(req.get("amount").asString()).isEqualTo("150.00");
        assertThat(req.get("note").asString()).isEqualTo("Settle up · Dinner");

        // Asking again while that request is open doesn't ask twice.
        assertThat(postJson("/api/v1/split-groups/" + id + "/settle", Map.of(), Bank.auth(a.session())).jsonBody()
                .size()).isZero();

        // b pays the request: real money moves, and the group is settled.
        Bank.deposit(this, b.session(), b.account(), "200.00");
        Map<String, String> headers = new HashMap<>(Bank.authWithKey(b.session(), UUID.randomUUID()));
        assertThat(postJson("/api/v1/payment-requests/" + req.get("id").asString() + "/pay",
                Map.of("fromAccountId", b.account().toString()), headers).status()).isEqualTo(201);
        assertThat(Bank.balance(this, a.session(), a.account())).isEqualTo("150.00");
        JsonNode group = get("/api/v1/split-groups/" + id, b.session().accessToken()).jsonBody();
        assertThat(balanceOf(group, a.handle())).isEqualByComparingTo("0");
        assertThat(balanceOf(group, b.handle())).isEqualByComparingTo("0");
    }

    @Test
    void aSettledMemberCanLeaveAndStaysInTheHistory() {
        Person a = person();
        Person b = person();
        Person c = person();
        String groupId = createGroup(a, "Flat", List.of(b.handle(), c.handle())).jsonBody().get("id").asString();
        assertThat(addExpense(a, groupId, a.handle(), "90.00", Map.of("type", "EQUAL")).status()).isEqualTo(201);

        // Owing or owed: not yet.
        Response owes = postJson("/api/v1/split-groups/" + groupId + "/leave", "", Bank.auth(b.session()));
        assertThat(owes.status()).isEqualTo(409);
        assertThat(owes.jsonBody().get("message").asString()).contains("You still owe the group 30.00");
        assertThat(postJson("/api/v1/split-groups/" + groupId + "/leave", "", Bank.auth(a.session())).status())
                .isEqualTo(409);

        // A member who owes nothing (an expense split only between a and b) can leave.
        String other = createGroup(a, "Trip", List.of(b.handle(), c.handle())).jsonBody().get("id").asString();
        assertThat(postJson("/api/v1/split-groups/" + other + "/leave", "", Bank.auth(c.session())).status())
                .isEqualTo(204);
        assertThat(get("/api/v1/split-groups", c.session().accessToken()).body()).doesNotContain(other);
        assertThat(get("/api/v1/split-groups/" + other, c.session().accessToken()).status()).isEqualTo(404);
        JsonNode group = get("/api/v1/split-groups/" + other, a.session().accessToken()).jsonBody();
        assertThat(group.get("members").toString()).contains("\"handle\":\"" + c.handle() + "\"").contains("\"left\":true");
        // New expenses are split between those still in the group.
        JsonNode e = addExpense(a, other, a.handle(), "100.00", Map.of("type", "EQUAL")).jsonBody();
        assertThat(e.get("shares").size()).isEqualTo(2);
        assertThat(addExpense(a, other, c.handle(), "10.00", Map.of("type", "EQUAL")).status()).isEqualTo(400);
    }

    private Response createGroup(Person p, String name, List<String> handles) {
        return postJson("/api/v1/split-groups", Map.of("name", name, "memberHandles", handles), Bank.auth(p.session()));
    }

    private Response addExpense(Person p, String groupId, String paidBy, String amount, Map<String, Object> split) {
        return postJson("/api/v1/split-groups/" + groupId + "/expenses", Map.of("paidByHandle", paidBy,
                "amount", amount, "description", "Groceries", "split", split), Bank.auth(p.session()));
    }

    private static String shareOf(JsonNode expense, String handle) {
        for (JsonNode s : expense.get("shares")) {
            if (s.get("handle").asString().equals(handle)) {
                return s.get("amount").asString();
            }
        }
        throw new AssertionError("no share for " + handle + " in " + expense);
    }

    private static BigDecimal balanceOf(JsonNode group, String handle) {
        for (JsonNode m : group.get("members")) {
            if (m.get("handle").asString().equals(handle)) {
                return new BigDecimal(m.get("balance").asString());
            }
        }
        throw new AssertionError("no member " + handle);
    }
}
