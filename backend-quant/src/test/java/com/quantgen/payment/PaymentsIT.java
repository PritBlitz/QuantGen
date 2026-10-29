package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** PRD §12.4 "Payments view": receipt numbers on every payment, one list of your own, filters and ownership. */
class PaymentsIT extends IntegrationTest {

    static final String POWER = "b1000000-0000-4000-8000-000000000001";

    private JsonNode list(TestUsers.Session s, String query) {
        Response r = get("/api/v1/payments" + query, s.accessToken());
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        return r.jsonBody().get("content");
    }

    @Test
    void everyPaymentHasAReceiptAndEachSideSeesOnlyItsOwn() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        TestUsers.Session eve = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "10000.00");

        // A transfer to Bob: Alice sees it OUT, Bob IN, under one receipt number.
        Response sent = Bank.transfer(this, alice, acc, Bank.firstAccountNumber(this, bob), "750.00", UUID.randomUUID());
        assertThat(sent.status()).isEqualTo(201);
        String receipt = sent.jsonBody().get("receiptNo").asString();
        assertThat(receipt).matches("QG\\d{13,}");
        JsonNode out = list(alice, "?kind=TRANSFER").get(0);
        assertThat(out.get("receiptNo").asString()).isEqualTo(receipt);
        assertThat(out.get("id").asString()).isEqualTo(receipt + ":OUT");
        assertThat(out.get("direction").asString()).isEqualTo("OUT");
        assertThat(out.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(out.get("amount").asString()).isEqualTo("750.00");
        assertThat(out.get("counterparty").asString()).startsWith("To ").contains("••••");
        assertThat(out.get("accountNumberMasked").asString()).startsWith("••••");
        assertThat(out.get("completedAt").isNull()).isFalse();
        JsonNode in = list(bob, "").get(0);
        assertThat(in.get("receiptNo").asString()).isEqualTo(receipt);
        assertThat(in.get("direction").asString()).isEqualTo("IN");
        assertThat(in.get("counterparty").asString()).startsWith("From ");
        assertThat(in.get("accountId").asString()).isEqualTo(Bank.firstAccountId(this, bob).toString());

        // A move between Alice's own accounts is one OUT row, not two.
        String goal = postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", "Trip", "target", "5000.00"),
                Bank.auth(alice)).jsonBody().get("accountNumber").asString();
        String own = Bank.transfer(this, alice, acc, goal, "100.00", UUID.randomUUID()).jsonBody().get("receiptNo").asString();
        assertThat(list(alice, "?q=" + own)).hasSize(1);

        // A bill payment and a top-up carry receipts too.
        JsonNode bill = postJson("/api/v1/billers/" + POWER + "/fetch", Map.of("consumerNumber", "9876543210"),
                Bank.auth(alice)).jsonBody();
        Response paid = postJson("/api/v1/bill-payments", Map.of("billId", bill.get("billId").asString(),
                "fromAccountId", acc.toString()), Bank.authWithKey(alice, UUID.randomUUID()));
        assertThat(paid.status()).isEqualTo(201);
        String billReceipt = paid.jsonBody().get("receiptNo").asString();
        JsonNode billRow = list(alice, "?kind=BILL").get(0);
        assertThat(billRow.get("receiptNo").asString()).isEqualTo(billReceipt);
        assertThat(billRow.get("counterparty").asString()).isEqualTo("Metro Power Distribution");
        jdbc.update("INSERT INTO topups (id, user_id, account_id, amount, gateway_order_id) VALUES (?, ?, ?, 300.00, "
                + "'order_PaymentsIT')", UUID.randomUUID(), alice.userId(), acc);
        JsonNode topup = list(alice, "?kind=TOPUP").get(0);
        assertThat(topup.get("status").asString()).isEqualTo("CREATED");
        assertThat(topup.get("direction").asString()).isEqualTo("IN");
        assertThat(topup.get("reference").asString()).isEqualTo("order_PaymentsIT");
        assertThat(topup.get("completedAt").isNull()).isTrue();
        assertThat(topup.get("receiptNo").asString()).isNotIn(receipt, own, billReceipt);

        // Everything, newest first; filters narrow it.
        assertThat(list(alice, "?size=100")).hasSize(4);
        assertThat(list(alice, "?status=CREATED")).hasSize(1);
        assertThat(list(alice, "?q=metro")).hasSize(1);
        assertThat(list(alice, "?q=" + receipt.toLowerCase())).hasSize(1);
        assertThat(list(alice, "?q=100%25")).isEmpty(); // LIKE wildcards are matched literally

        // One payment by number: your side only; someone else's number is 404.
        assertThat(get("/api/v1/payments/" + receipt, bob.accessToken()).jsonBody().get("direction").asString())
                .isEqualTo("IN");
        assertThat(get("/api/v1/payments/" + receipt, alice.accessToken()).jsonBody().get("sourceId").asString())
                .isEqualTo(sent.jsonBody().get("id").asString());
        assertThat(get("/api/v1/payments/" + receipt, eve.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/payments/not-a-receipt", eve.accessToken()).status()).isEqualTo(404);
        assertThat(list(eve, "")).isEmpty();
        assertThat(get("/api/v1/payments").status()).isEqualTo(401);
    }

    @Test
    void aHeldTransferIsNeverShownToTheReceiver() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        JsonNode from = Bank.firstAccount(this, alice);
        JsonNode to = Bank.firstAccount(this, bob);
        // Held for review, as risk scoring does (no ledger entry yet): the sender sees it, the receiver doesn't.
        jdbc.update("""
                INSERT INTO transfers (user_id, from_account_id, to_account_id, from_account_number, to_account_number,
                                       to_name, amount, status, source)
                VALUES (?, ?::uuid, ?::uuid, ?, ?, 'Bob', 200.00, 'PENDING_REVIEW', 'MANUAL')
                """, alice.userId(), from.get("id").asString(), to.get("id").asString(),
                from.get("accountNumber").asString(), to.get("accountNumber").asString());
        JsonNode held = list(alice, "?status=PENDING_REVIEW");
        assertThat(held).hasSize(1);
        assertThat(held.get(0).get("counterparty").asString()).isEqualTo("To Bob ••••"
                + to.get("accountNumber").asString().substring(8));
        assertThat(held.get(0).get("completedAt").isNull()).isTrue();
        assertThat(list(bob, "")).isEmpty();
    }

    @Test
    void badFiltersAreRefused() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        assertThat(get("/api/v1/payments?kind=LOAN", s.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payments?status=PAID", s.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payments?from=2026-10-30&to=2026-10-01", s.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payments?q=" + "x".repeat(51), s.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payments?size=101", s.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/payments?from=2026-10-01&to=2026-10-30", s.accessToken()).status()).isEqualTo(200);
    }
}
