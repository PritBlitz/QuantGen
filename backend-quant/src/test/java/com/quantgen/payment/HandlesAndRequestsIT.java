package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M2 part 1: handles (F-PAY-5), confirmation of payee (F-PAY-6), transfers by handle, payment requests (F-PAY-8). */
class HandlesAndRequestsIT extends IntegrationTest {

    @Autowired
    LedgerService ledger;

    // ------------------------------------------------------------------ handles

    @Test
    void handleIsValidatedNormalisedAndShownOnTheUser() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);

        assertThat(setHandle(alice, "ab@qg", acc).status()).isEqualTo(400);
        assertThat(setHandle(alice, "alice", acc).status()).isEqualTo(400);
        assertThat(setHandle(alice, ".alice@qg", acc).status()).isEqualTo(400);
        assertThat(setHandle(alice, "al..ice@qg", acc).status()).isEqualTo(400);
        assertThat(setHandle(alice, "support@qg", acc).status()).isEqualTo(409);

        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        assertThat(setHandle(alice, unique("x"), Bank.firstAccountId(this, bob)).status()).isEqualTo(404);

        String handle = unique("alice");
        Response ok = setHandle(alice, "  " + handle.toUpperCase() + " ", acc);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.jsonBody().get("handle").asString()).isEqualTo(handle);
        assertThat(ok.jsonBody().get("accountId").asString()).isEqualTo(acc.toString());
        assertThat(ok.jsonBody().get("changeableAt").asString()).isNotBlank();
        assertThat(get("/api/v1/users/me", alice.accessToken()).jsonBody().get("handle").asString()).isEqualTo(handle);
        assertThat(get("/api/v1/users/me/handle", alice.accessToken()).jsonBody().get("handle").asString())
                .isEqualTo(handle);
        assertThat(get("/api/v1/users/me/handle", bob.accessToken()).status()).isEqualTo(404);

        // Taken by someone else.
        Response taken = setHandle(bob, handle, Bank.firstAccountId(this, bob));
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("handle");
    }

    @Test
    void handleChangesOncePerThirtyDaysButCanBeRepointed() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        UUID checking = openChecking(alice);
        String first = unique("first");
        assertThat(setHandle(alice, first, savings).status()).isEqualTo(200);

        // Same handle, other account: always allowed.
        Response repointed = setHandle(alice, first, checking);
        assertThat(repointed.status()).isEqualTo(200);
        assertThat(repointed.jsonBody().get("accountId").asString()).isEqualTo(checking.toString());

        // A different handle within 30 days: refused.
        Response tooSoon = setHandle(alice, unique("second"), savings);
        assertThat(tooSoon.status()).isEqualTo(422);
        assertThat(tooSoon.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");

        // After 30 days it works, and the old handle stays reserved for alice.
        jdbc.update("UPDATE handles SET changed_at = now() - interval '31 days' WHERE user_id = ?", alice.userId());
        String second = unique("second");
        assertThat(setHandle(alice, second, savings).status()).isEqualTo(200);
        TestUsers.Session mallory = TestUsers.registerAndLogin(this);
        assertThat(setHandle(mallory, first, Bank.firstAccountId(this, mallory)).status()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'HANDLE_SET' AND actor_user_id = ?",
                Integer.class, alice.userId())).isEqualTo(2);
    }

    // ------------------------------------------------------------------ resolve

    @Test
    void resolveShowsAMaskedNameAndIsRateLimited() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        String bobHandle = unique("bob");
        setHandle(bob, bobHandle, Bank.firstAccountId(this, bob));
        String bobNumber = Bank.firstAccountNumber(this, bob);

        JsonNode byHandle = get("/api/v1/payees/resolve?handle=" + bobHandle.toUpperCase(), alice.accessToken()).jsonBody();
        assertThat(byHandle.get("holderNameMasked").asString()).isEqualTo("Test U.");
        assertThat(byHandle.get("handle").asString()).isEqualTo(bobHandle);
        assertThat(byHandle.get("accountNumberMasked").asString()).isEqualTo("••••" + bobNumber.substring(8));

        JsonNode byNumber = get("/api/v1/payees/resolve?accountNumber=" + bobNumber, alice.accessToken()).jsonBody();
        assertThat(byNumber.get("handle").asString()).isEqualTo(bobHandle);
        assertThat(byNumber.toString()).doesNotContain(bobNumber).doesNotContain("Test User");

        assertThat(get("/api/v1/payees/resolve?handle=" + unique("nobody"), alice.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/payees/resolve", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payees/resolve?handle=" + bobHandle + "&accountNumber=" + bobNumber,
                alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payees/resolve?handle=" + bobHandle).status()).isEqualTo(401);

        // 30 lookups per minute per user; this user has used 3 counted lookups above (the 400s don't count).
        int lastStatus = 0;
        Response limited = null;
        for (int i = 0; i < 30 && lastStatus != 429; i++) {
            limited = get("/api/v1/payees/resolve?handle=" + bobHandle, alice.accessToken());
            lastStatus = limited.status();
        }
        assertThat(lastStatus).isEqualTo(429);
        assertThat(limited.header("Retry-After")).isNotBlank();
        // Another user is unaffected.
        assertThat(get("/api/v1/payees/resolve?handle=" + bobHandle, bob.accessToken()).status()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ transfers by handle

    @Test
    void transferByHandlePaysTheHandlesAccount() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bChecking = openChecking(bob);
        String bobHandle = unique("bob");
        setHandle(bob, bobHandle, bChecking);
        Bank.deposit(this, alice, aAcc, "1000.00");

        Response ok = transferToHandle(alice, aAcc, bobHandle.toUpperCase(), "250.00", UUID.randomUUID());
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.jsonBody().get("source").asString()).isEqualTo("MANUAL");
        assertThat(Bank.balance(this, bob, bChecking)).isEqualTo("250.00");
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("750.00");

        Response unknown = transferToHandle(alice, aAcc, unique("ghost"), "1.00", UUID.randomUUID());
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("toHandle");

        Response both = postJson("/api/v1/transfers", Map.of("fromAccountId", aAcc.toString(),
                "toAccountNumber", Bank.firstAccountNumber(this, bob), "toHandle", bobHandle, "amount", "1.00"),
                Bank.authWithKey(alice, UUID.randomUUID()));
        assertThat(both.status()).isEqualTo(400);
        Response neither = postJson("/api/v1/transfers", Map.of("fromAccountId", aAcc.toString(), "amount", "1.00"),
                Bank.authWithKey(alice, UUID.randomUUID()));
        assertThat(neither.status()).isEqualTo(400);
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("750.00");
    }

    // ------------------------------------------------------------------ payment requests

    @Test
    void requestCanBePaidOnceAndLinksTheTransfer() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this); // asks
        TestUsers.Session bob = TestUsers.registerAndLogin(this);   // pays
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        Bank.deposit(this, bob, bAcc, "1000.00");

        // No handle yet: can't ask.
        assertThat(createRequest(alice, unique("x"), "10.00").status()).isEqualTo(409);
        String aliceHandle = unique("alice");
        String bobHandle = unique("bob");
        setHandle(alice, aliceHandle, aAcc);
        setHandle(bob, bobHandle, bAcc);
        assertThat(createRequest(alice, aliceHandle, "10.00").status()).isEqualTo(400); // yourself

        Response created = createRequest(alice, bobHandle, "300.00");
        assertThat(created.status()).isEqualTo(201);
        JsonNode req = created.jsonBody();
        assertThat(req.get("status").asString()).isEqualTo("OPEN");
        assertThat(req.get("fromHandle").asString()).isEqualTo(aliceHandle);
        assertThat(req.get("toHandle").asString()).isEqualTo(bobHandle);
        String id = req.get("id").asString();
        assertThat(get("/api/v1/notifications", bob.accessToken()).body()).contains("PAYMENT_REQUEST");

        assertThat(get("/api/v1/payment-requests", bob.accessToken()).jsonBody().get("content").get(0).get("id").asString())
                .isEqualTo(id);
        assertThat(get("/api/v1/payment-requests?direction=OUTGOING", alice.accessToken()).jsonBody().get("totalElements")
                .asInt()).isEqualTo(1);
        assertThat(get("/api/v1/payment-requests", alice.accessToken()).jsonBody().get("totalElements").asInt()).isZero();

        // Only the payer can pay it; anyone else gets 404.
        TestUsers.Session eve = TestUsers.registerAndLogin(this);
        assertThat(pay(eve, id, Bank.firstAccountId(this, eve), UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(pay(alice, id, aAcc, UUID.randomUUID()).status()).isEqualTo(404);

        UUID key = UUID.randomUUID();
        Response paid = pay(bob, id, bAcc, key);
        assertThat(paid.status()).isEqualTo(201);
        assertThat(paid.jsonBody().get("source").asString()).isEqualTo("REQUEST");
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("300.00");
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("700.00");

        // Replay with the same key: same transfer, no new money. A new key: the request is no longer open.
        Response replay = pay(bob, id, bAcc, key);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.jsonBody().get("id").asString()).isEqualTo(paid.jsonBody().get("id").asString());
        assertThat(pay(bob, id, bAcc, UUID.randomUUID()).status()).isEqualTo(409);
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("700.00");

        JsonNode after = get("/api/v1/payment-requests?status=PAID", bob.accessToken()).jsonBody().get("content").get(0);
        assertThat(after.get("transferId").asString()).isEqualTo(paid.jsonBody().get("id").asString());
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void concurrentPaysWithDifferentKeysPayOnce() throws Exception {
        Pair p = pairWithHandles();
        Bank.deposit(this, p.bob, p.bAcc, "1000.00");
        String id = createRequest(p.alice, p.bobHandle, "100.00").jsonBody().get("id").asString();

        List<Integer> statuses = runConcurrently(10, i -> pay(p.bob, id, p.bAcc, UUID.randomUUID()).status());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(9);
        assertThat(Bank.balance(this, p.bob, p.bAcc)).isEqualTo("900.00");
        assertThat(Bank.balance(this, p.alice, p.aAcc)).isEqualTo("100.00");
    }

    @Test
    void largeRequestNeedsStepUpAndFundsAreChecked() {
        Pair p = pairWithHandles();
        String id = createRequest(p.alice, p.bobHandle, "60000.00").jsonBody().get("id").asString();

        UUID key = UUID.randomUUID();
        Response stepUp = pay(p.bob, id, p.bAcc, key);
        assertThat(stepUp.status()).isEqualTo(401);
        assertThat(stepUp.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        Map<String, String> headers = new HashMap<>(Bank.authWithKey(p.bob, key));
        headers.put("X-Step-Up-Token", Bank.stepUpToken(this, p.bob, "TRANSFER"));
        Response broke = postJson("/api/v1/payment-requests/" + id + "/pay", Map.of("fromAccountId", p.bAcc.toString()),
                headers);
        assertThat(broke.status()).isEqualTo(422);
        assertThat(broke.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");

        Bank.deposit(this, p.bob, p.bAcc, "60000.00");
        Response ok = postJson("/api/v1/payment-requests/" + id + "/pay", Map.of("fromAccountId", p.bAcc.toString()),
                headers);
        assertThat(ok.status()).isEqualTo(201);
    }

    @Test
    void declineBlockCancelAndExpiry() {
        Pair p = pairWithHandles();
        String first = createRequest(p.alice, p.bobHandle, "10.00").jsonBody().get("id").asString();
        String second = createRequest(p.alice, p.bobHandle, "20.00").jsonBody().get("id").asString();

        // The requester cancels one of their own.
        Response cancelled = postJson("/api/v1/payment-requests/" + second + "/decline", Map.of(), Bank.auth(p.alice));
        assertThat(cancelled.jsonBody().get("status").asString()).isEqualTo("CANCELLED");

        // Someone unrelated can't touch it.
        TestUsers.Session eve = TestUsers.registerAndLogin(this);
        assertThat(postJson("/api/v1/payment-requests/" + first + "/decline", Map.of(), Bank.auth(eve)).status())
                .isEqualTo(404);

        // The payer declines and blocks: alice can't ask bob again.
        Response declined = postJson("/api/v1/payment-requests/" + first + "/decline", Map.of("block", true),
                Bank.auth(p.bob));
        assertThat(declined.jsonBody().get("status").asString()).isEqualTo("DECLINED");
        assertThat(postJson("/api/v1/payment-requests/" + first + "/decline", Map.of(), Bank.auth(p.bob)).status())
                .isEqualTo(409);
        assertThat(createRequest(p.alice, p.bobHandle, "5.00").status()).isEqualTo(400);

        // Expiry: bob asks alice, the request ages past 7 days, and it reads EXPIRED and can't be paid.
        String old = createRequest(p.bob, p.aliceHandle, "5.00").jsonBody().get("id").asString();
        jdbc.update("UPDATE payment_requests SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", old);
        JsonNode listed = get("/api/v1/payment-requests?status=EXPIRED", p.alice.accessToken()).jsonBody();
        assertThat(listed.get("content").get(0).get("id").asString()).isEqualTo(old);
        Bank.deposit(this, p.alice, p.aAcc, "100.00");
        assertThat(pay(p.alice, old, p.aAcc, UUID.randomUUID()).status()).isEqualTo(409);
    }

    @Test
    void atMostTwentyOpenRequests() {
        Pair p = pairWithHandles();
        for (int i = 0; i < 20; i++) {
            assertThat(createRequest(p.alice, p.bobHandle, "1.00").status()).isEqualTo(201);
        }
        Response over = createRequest(p.alice, p.bobHandle, "1.00");
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
    }

    // ------------------------------------------------------------------ helpers

    private record Pair(TestUsers.Session alice, TestUsers.Session bob, UUID aAcc, UUID bAcc, String aliceHandle,
                        String bobHandle) {
    }

    private Pair pairWithHandles() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        String aliceHandle = unique("alice");
        String bobHandle = unique("bob");
        assertThat(setHandle(alice, aliceHandle, aAcc).status()).isEqualTo(200);
        assertThat(setHandle(bob, bobHandle, bAcc).status()).isEqualTo(200);
        return new Pair(alice, bob, aAcc, bAcc, aliceHandle, bobHandle);
    }

    static String unique(String prefix) {
        return prefix + "." + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@qg";
    }

    Response setHandle(TestUsers.Session s, String handle, UUID accountId) {
        return put("/api/v1/users/me/handle", Map.of("handle", handle, "accountId", accountId.toString()),
                s.accessToken());
    }

    UUID openChecking(TestUsers.Session s) {
        Response res = postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(s));
        assertThat(res.status()).isEqualTo(201);
        return UUID.fromString(res.jsonBody().get("id").asString());
    }

    Response transferToHandle(TestUsers.Session s, UUID from, String handle, String amount, UUID key) {
        return postJson("/api/v1/transfers", Map.of("fromAccountId", from.toString(), "toHandle", handle,
                "amount", amount), Bank.authWithKey(s, key));
    }

    Response createRequest(TestUsers.Session s, String toHandle, String amount) {
        return postJson("/api/v1/payment-requests", Map.of("toHandle", toHandle, "amount", amount, "note", "Dinner"),
                Bank.auth(s));
    }

    Response pay(TestUsers.Session s, String requestId, UUID from, UUID key) {
        return postJson("/api/v1/payment-requests/" + requestId + "/pay", Map.of("fromAccountId", from.toString()),
                Bank.authWithKey(s, key));
    }

    private interface Task<T> {
        T run(int i) throws Exception;
    }

    private static <T> List<T> runConcurrently(int n, Task<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                Callable<T> c = () -> {
                    start.await();
                    return task.run(idx);
                };
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
