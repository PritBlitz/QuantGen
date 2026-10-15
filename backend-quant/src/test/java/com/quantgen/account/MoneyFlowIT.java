package com.quantgen.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** M2 over HTTP: accounts, deposits, idempotency, transfers, history, statement, beneficiaries, reconcile. */
class MoneyFlowIT extends IntegrationTest {

    // ------------------------------------------------------------------ accounts

    @Test
    void accountsListCreateAndLimit() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        JsonNode first = Bank.firstAccount(this, s);
        assertThat(first.get("type").asString()).isEqualTo("SAVINGS");
        assertThat(first.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(first.get("balance").asString()).isEqualTo("0.00");
        assertThat(first.get("currency").asString()).isEqualTo("INR");
        assertThat(first.get("accountNumber").asString()).matches("[0-9]{12}");

        Response created = postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(s));
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.jsonBody().get("type").asString()).isEqualTo("CHECKING");
        assertThat(postJson("/api/v1/accounts", Map.of("type", "SAVINGS"), Bank.auth(s)).status()).isEqualTo(201);

        Response fourth = postJson("/api/v1/accounts", Map.of("type", "SAVINGS"), Bank.auth(s));
        assertThat(fourth.status()).isEqualTo(422);
        assertThat(fourth.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(get("/api/v1/accounts", s.accessToken()).jsonBody().size()).isEqualTo(3);

        Response badType = postJson("/api/v1/accounts", Map.of("type", "GOLD"), Bank.auth(s));
        assertThat(badType.status()).isEqualTo(400);
    }

    @Test
    void anotherUsersAccountIsNotFound() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID bobsAccount = Bank.firstAccountId(this, bob);

        assertThat(get("/api/v1/accounts/" + bobsAccount, alice.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/accounts/" + bobsAccount + "/transactions", alice.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/accounts/" + bobsAccount + "/statement", alice.accessToken()).status()).isEqualTo(404);
        Response dep = Bank.deposit(this, alice, bobsAccount, "10.00");
        assertThat(dep.status()).isEqualTo(404);
        assertThat(dep.jsonBody().get("code").asString()).isEqualTo("NOT_FOUND");
        Response steal = Bank.transfer(this, alice, bobsAccount, Bank.firstAccountNumber(this, alice), "1.00",
                UUID.randomUUID());
        assertThat(steal.status()).isEqualTo(404);
        assertThat(get("/api/v1/accounts/not-a-uuid", alice.accessToken()).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ deposits and idempotency

    @Test
    void depositCreditsAccountAndReturnsTransaction() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);

        Response res = Bank.deposit(this, s, acc, "1250.50");
        assertThat(res.status()).isEqualTo(201);
        JsonNode tx = res.jsonBody();
        assertThat(tx.get("amount").asString()).isEqualTo("1250.50");
        assertThat(tx.get("balanceAfter").asString()).isEqualTo("1250.50");
        assertThat(tx.get("direction").asString()).isEqualTo("CREDIT");
        assertThat(tx.get("type").asString()).isEqualTo("DEPOSIT");
        assertThat(tx.get("accountId").asString()).isEqualTo(acc.toString());
        assertThat(tx.get("journalEntryId").asString()).hasSize(36);
        assertThat(Bank.balance(this, s, acc)).isEqualTo("1250.50");
    }

    @Test
    void depositValidation() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        for (String bad : List.of("0", "0.99", "-5", "100000.01", "10.001", "abc")) {
            Response res = Bank.deposit(this, s, acc, bad);
            assertThat(res.status()).as("amount %s", bad).isEqualTo(400);
            assertThat(res.jsonBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(Bank.deposit(this, s, acc, "100000.00").status()).isEqualTo(201);

        Response numeric = postJson("/api/v1/accounts/" + acc + "/deposit", "{\"amount\": 5.25}",
                Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(numeric.status()).isEqualTo(201);
        assertThat(Bank.balance(this, s, acc)).isEqualTo("100005.25");
    }

    @Test
    void idempotencyKeyIsRequiredAndMustBeAUuid() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);

        Response missing = postJson("/api/v1/accounts/" + acc + "/deposit", Map.of("amount", "10"), Bank.auth(s));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("Idempotency-Key");

        Map<String, String> h = new java.util.HashMap<>(Bank.auth(s));
        h.put("Idempotency-Key", "not-a-uuid");
        Response bad = postJson("/api/v1/accounts/" + acc + "/deposit", Map.of("amount", "10"), h);
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.jsonBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(Bank.balance(this, s, acc)).isEqualTo("0.00");
    }

    @Test
    void sameKeySameBodyReplaysWithoutMovingMoneyTwice() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        UUID key = UUID.randomUUID();

        Response first = Bank.deposit(this, s, acc, "500.00", key);
        Response replay = Bank.deposit(this, s, acc, "500.00", key);
        Response replaySameValue = Bank.deposit(this, s, acc, "500", key); // same amount, different spelling

        assertThat(first.status()).isEqualTo(201);
        assertThat(first.header("Idempotent-Replayed")).isNull();
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.jsonBody()).isEqualTo(first.jsonBody());
        assertThat(replaySameValue.status()).isEqualTo(201);
        assertThat(Bank.balance(this, s, acc)).isEqualTo("500.00");
    }

    @Test
    void sameKeyDifferentBodyIsAConflict() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        UUID key = UUID.randomUUID();
        assertThat(Bank.deposit(this, s, acc, "500.00", key).status()).isEqualTo(201);

        Response conflict = Bank.deposit(this, s, acc, "600.00", key);
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.jsonBody().get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");

        // Reusing the key on a different endpoint is also a conflict.
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        Response crossEndpoint = Bank.transfer(this, s, acc, Bank.firstAccountNumber(this, other), "1.00", key);
        assertThat(crossEndpoint.status()).isEqualTo(409);
        assertThat(Bank.balance(this, s, acc)).isEqualTo("500.00");
    }

    @Test
    void keysAreScopedPerUser() {
        TestUsers.Session a = TestUsers.registerAndLogin(this);
        TestUsers.Session b = TestUsers.registerAndLogin(this);
        UUID key = UUID.randomUUID();
        assertThat(Bank.deposit(this, a, Bank.firstAccountId(this, a), "10.00", key).status()).isEqualTo(201);
        assertThat(Bank.deposit(this, b, Bank.firstAccountId(this, b), "10.00", key).status()).isEqualTo(201);
        assertThat(Bank.balance(this, b, Bank.firstAccountId(this, b))).isEqualTo("10.00");
    }

    @Test
    void failedRequestDoesNotBurnTheKey() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session r = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        UUID key = UUID.randomUUID();

        Response broke = Bank.transfer(this, s, acc, Bank.firstAccountNumber(this, r), "100.00", key);
        assertThat(broke.status()).isEqualTo(422);
        assertThat(broke.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");

        Bank.deposit(this, s, acc, "100.00");
        Response retry = Bank.transfer(this, s, acc, Bank.firstAccountNumber(this, r), "100.00", key);
        assertThat(retry.status()).isEqualTo(201);
        assertThat(Bank.balance(this, s, acc)).isEqualTo("0.00");
    }

    // ------------------------------------------------------------------ transfers

    @Test
    void transferMovesMoneyAndNotifiesRecipient() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        String bNum = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, aAcc, "1000.00");

        Response res = postJson("/api/v1/transfers", Map.of("fromAccountId", aAcc.toString(), "toAccountNumber", bNum,
                "amount", "250.75", "note", "Dinner"), Bank.authWithKey(alice, UUID.randomUUID()));
        assertThat(res.status()).isEqualTo(201);
        JsonNode t = res.jsonBody();
        assertThat(t.get("toAccountNumber").asString()).isEqualTo(bNum);
        assertThat(t.get("fromAccountNumber").asString()).isEqualTo(Bank.firstAccountNumber(this, alice));
        assertThat(t.get("toName").asString()).isEqualTo("Test User");
        assertThat(t.get("amount").asString()).isEqualTo("250.75");
        assertThat(t.get("note").asString()).isEqualTo("Dinner");
        assertThat(t.get("status").asString()).isEqualTo("COMPLETED");

        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("749.25");
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("250.75");

        JsonNode bobTx = get("/api/v1/accounts/" + bAcc + "/transactions", bob.accessToken()).jsonBody();
        assertThat(bobTx.get("totalElements").asLong()).isEqualTo(1);
        JsonNode line = bobTx.get("content").get(0);
        assertThat(line.get("direction").asString()).isEqualTo("CREDIT");
        assertThat(line.get("type").asString()).isEqualTo("TRANSFER");
        assertThat(line.get("description").asString()).isEqualTo("Dinner");
        assertThat(line.get("counterparty").asString()).startsWith("From Test User ••••");
        assertThat(line.get("counterparty").asString()).doesNotContain(Bank.firstAccountNumber(this, alice));

        Integer notes = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'MONEY_RECEIVED'",
                Integer.class, bob.userId());
        assertThat(notes).isEqualTo(1);

        JsonNode list = get("/api/v1/transfers", alice.accessToken()).jsonBody();
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.get("page").asInt()).isZero();
        assertThat(list.get("size").asInt()).isEqualTo(20);
        assertThat(get("/api/v1/transfers", bob.accessToken()).jsonBody().get("totalElements").asLong()).isZero();
    }

    @Test
    void transferValidationAndRules() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        String aNum = Bank.firstAccountNumber(this, alice);
        String bNum = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, aAcc, "100000.00");
        Bank.deposit(this, alice, aAcc, "100000.00");
        Bank.deposit(this, alice, aAcc, "100000.00");

        assertCode(Bank.transfer(this, alice, aAcc, bNum, "0.99", UUID.randomUUID()), 400, "VALIDATION_FAILED");
        assertCode(Bank.transfer(this, alice, aAcc, bNum, "200000.01", UUID.randomUUID()), 400, "VALIDATION_FAILED");
        assertCode(Bank.transfer(this, alice, aAcc, "12345", "10", UUID.randomUUID()), 400, "VALIDATION_FAILED");
        assertCode(Bank.transfer(this, alice, aAcc, "999999999999", "10", UUID.randomUUID()), 400, "VALIDATION_FAILED");
        Response self = Bank.transfer(this, alice, aAcc, aNum, "10", UUID.randomUUID());
        assertCode(self, 400, "VALIDATION_FAILED");
        assertThat(self.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("toAccountNumber");

        Response withControlChars = postJson("/api/v1/transfers", Map.of("fromAccountId", aAcc.toString(),
                "toAccountNumber", bNum, "amount", "10", "note", "bad\nnote"), Bank.authWithKey(alice, UUID.randomUUID()));
        assertCode(withControlChars, 400, "VALIDATION_FAILED");

        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("300000.00");
    }

    @Test
    void dailyOutgoingLimitExcludesOwnAccountTransfers() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        String bNum = Bank.firstAccountNumber(this, bob);
        for (int i = 0; i < 7; i++) {
            Bank.deposit(this, alice, aAcc, "100000.00");
        }
        String ownNumber = postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice))
                .jsonBody().get("accountNumber").asString();

        // Own-account moves don't count.
        assertThat(Bank.transfer(this, alice, aAcc, ownNumber, "200000.00", UUID.randomUUID()).status()).isEqualTo(201);

        assertThat(Bank.transfer(this, alice, aAcc, bNum, "200000.00", UUID.randomUUID()).status()).isEqualTo(201);
        assertThat(Bank.transfer(this, alice, aAcc, bNum, "200000.00", UUID.randomUUID()).status()).isEqualTo(201);
        Response over = Bank.transfer(this, alice, aAcc, bNum, "100000.01", UUID.randomUUID());
        assertCode(over, 422, "LIMIT_EXCEEDED");
        assertThat(over.jsonBody().get("message").asString()).contains("100000.00");
        assertThat(Bank.transfer(this, alice, aAcc, bNum, "100000.00", UUID.randomUUID()).status()).isEqualTo(201);
        assertCode(Bank.transfer(this, alice, aAcc, bNum, "1.00", UUID.randomUUID()), 422, "LIMIT_EXCEEDED");
    }

    @Test
    void frozenAccountCannotSendButCanReceive() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        Bank.deposit(this, alice, aAcc, "100.00");
        Bank.deposit(this, bob, bAcc, "100.00");
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", aAcc);

        assertCode(Bank.transfer(this, alice, aAcc, Bank.firstAccountNumber(this, bob), "10", UUID.randomUUID()),
                422, "ACCOUNT_FROZEN");
        assertThat(Bank.transfer(this, bob, bAcc, Bank.firstAccountNumber(this, alice), "10", UUID.randomUUID()).status())
                .isEqualTo(201);
        assertThat(Bank.deposit(this, alice, aAcc, "5.00").status()).isEqualTo(201);
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("115.00");
    }

    // ------------------------------------------------------------------ history and statement

    @Test
    void historyIsPagedAndFilterable() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        for (int i = 1; i <= 5; i++) {
            Bank.deposit(this, alice, aAcc, i + "00.00");
        }
        Bank.transfer(this, alice, aAcc, Bank.firstAccountNumber(this, bob), "50.00", UUID.randomUUID());

        JsonNode page0 = get("/api/v1/accounts/" + aAcc + "/transactions?page=0&size=4", alice.accessToken()).jsonBody();
        assertThat(page0.get("totalElements").asLong()).isEqualTo(6);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("content").size()).isEqualTo(4);
        JsonNode newest = page0.get("content").get(0);
        assertThat(newest.get("type").asString()).isEqualTo("TRANSFER");
        assertThat(newest.get("direction").asString()).isEqualTo("DEBIT");
        assertThat(newest.get("balanceAfter").asString()).isEqualTo("1450.00");

        JsonNode deposits = get("/api/v1/accounts/" + aAcc + "/transactions?type=DEPOSIT", alice.accessToken()).jsonBody();
        assertThat(deposits.get("totalElements").asLong()).isEqualTo(5);

        String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).toString();
        String tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).toString();
        assertThat(get("/api/v1/accounts/" + aAcc + "/transactions?from=" + today + "&to=" + today, alice.accessToken())
                .jsonBody().get("totalElements").asLong()).isEqualTo(6);
        assertThat(get("/api/v1/accounts/" + aAcc + "/transactions?from=" + tomorrow, alice.accessToken())
                .jsonBody().get("totalElements").asLong()).isZero();

        assertThat(get("/api/v1/accounts/" + aAcc + "/transactions?size=101", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/accounts/" + aAcc + "/transactions?type=BOGUS", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/accounts/" + aAcc + "/transactions?from=" + tomorrow + "&to=" + today, alice.accessToken())
                .status()).isEqualTo(400);
    }

    @Test
    void statementIsCsvWithFormulaInjectionNeutralized() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, aAcc, "100.00");
        postJson("/api/v1/transfers", Map.of("fromAccountId", aAcc.toString(),
                        "toAccountNumber", Bank.firstAccountNumber(this, bob), "amount", "10", "note", "=HYPERLINK(\"x\")"),
                Bank.authWithKey(alice, UUID.randomUUID()));

        Response csv = get("/api/v1/accounts/" + aAcc + "/statement?format=csv", alice.accessToken());
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.header("Content-Type")).startsWith("text/csv");
        assertThat(csv.header("Content-Disposition")).contains("attachment").contains(".csv");
        String body = csv.body();
        assertThat(body).contains("\"Date (IST)\",\"Type\",\"Description\"");
        assertThat(body).contains("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(body).doesNotContain(",\"=HYPERLINK");
        assertThat(body).contains("\"100.00\"").contains("\"90.00\"");

        assertThat(get("/api/v1/accounts/" + aAcc + "/statement?format=pdf", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/accounts/" + aAcc + "/statement?from=2024-01-01&to=2026-01-01", alice.accessToken())
                .status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ beneficiaries

    @Test
    void beneficiariesLifecycle() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        String bNum = Bank.firstAccountNumber(this, bob);

        Response added = postJson("/api/v1/beneficiaries", Map.of("nickname", "Bob", "accountNumber", bNum), Bank.auth(alice));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.jsonBody().get("holderName").asString()).isEqualTo("Test User");
        assertThat(added.jsonBody().get("accountNumber").asString()).isEqualTo(bNum);

        Response dup = postJson("/api/v1/beneficiaries", Map.of("nickname", "Bob 2", "accountNumber", bNum), Bank.auth(alice));
        assertCode(dup, 409, "VALIDATION_FAILED");
        assertCode(postJson("/api/v1/beneficiaries", Map.of("nickname", "X", "accountNumber", "999999999999"),
                Bank.auth(alice)), 400, "VALIDATION_FAILED");
        assertCode(postJson("/api/v1/beneficiaries", Map.of("nickname", "Me",
                "accountNumber", Bank.firstAccountNumber(this, alice)), Bank.auth(alice)), 400, "VALIDATION_FAILED");

        JsonNode list = get("/api/v1/beneficiaries", alice.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(1);
        String id = list.get(0).get("id").asString();

        // Bob can't delete Alice's beneficiary.
        assertThat(send(request("/api/v1/beneficiaries/" + id).header("Authorization", "Bearer " + bob.accessToken())
                .DELETE().build()).status()).isEqualTo(404);
        assertThat(send(request("/api/v1/beneficiaries/" + id).header("Authorization", "Bearer " + alice.accessToken())
                .DELETE().build()).status()).isEqualTo(204);
        assertThat(get("/api/v1/beneficiaries", alice.accessToken()).jsonBody().size()).isZero();
    }

    // ------------------------------------------------------------------ reconcile

    @Test
    void reconcileIsAdminOnlyAndBalanced() {
        TestUsers.Session customer = TestUsers.registerAndLogin(this);
        assertThat(get("/api/v1/admin/ledger/reconcile", customer.accessToken()).status()).isEqualTo(403);

        TestUsers.Session admin = Bank.admin(this);
        Response res = get("/api/v1/admin/ledger/reconcile", admin.accessToken());
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.jsonBody().get("balanced").asBoolean()).isTrue();
        assertThat(res.jsonBody().get("ledgerSum").asString()).isEqualTo("0.00");
        assertThat(res.jsonBody().get("mismatches").size()).isZero();
    }

    private static void assertCode(Response res, int status, String code) {
        List<String> context = new ArrayList<>();
        context.add(res.body());
        assertThat(res.status()).as("%s", context).isEqualTo(status);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo(code);
    }
}
