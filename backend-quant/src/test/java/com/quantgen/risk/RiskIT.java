package com.quantgen.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/**
 * V2-M6 rules-based risk: a scripted fraud scenario is held, then released or rejected end to end; card fraud
 * declines; loan documents. The rules are on here only (they read the wall clock, see application-test.yml).
 */
@TestPropertySource(properties = "qg.risk.enabled=true")
class RiskIT extends IntegrationTest {

    @Autowired
    LedgerService ledger;

    @AfterEach
    void cardsOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'cards'");
    }

    record Scenario(TestUsers.Session victim, UUID from, TestUsers.Session mule, UUID muleAccount, JsonNode transfer) {
    }

    /**
     * Three ₹1,000 payments to a friend, then ₹30,000 to someone never paid before: 30× the usual amount to a new
     * payee (and round amounts), so the score is at least 35 + 25 = 60 at any time of day.
     */
    private Scenario scam() {
        TestUsers.Session victim = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        TestUsers.Session mule = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, victim);
        assertThat(Bank.deposit(this, victim, from, "50000.00").status()).isEqualTo(201);
        String friendNumber = Bank.firstAccountNumber(this, friend);
        for (int i = 0; i < 3; i++) {
            assertThat(Bank.transfer(this, victim, from, friendNumber, "1000.00", UUID.randomUUID()).jsonBody()
                    .get("status").asString()).isEqualTo("COMPLETED");
        }
        Response res = Bank.transfer(this, victim, from, Bank.firstAccountNumber(this, mule), "30000.00", UUID.randomUUID());
        assertThat(res.status()).isEqualTo(201);
        return new Scenario(victim, from, mule, Bank.firstAccountId(this, mule), res.jsonBody());
    }

    @Test
    void aScriptedScamIsHeldThenReleasedEndToEnd() {
        Scenario s = scam();
        assertThat(s.transfer().get("status").asString()).isEqualTo("PENDING_REVIEW");
        assertAccount(s.victim(), s.from(), "47000.00", "30000.00", "17000.00"); // held, not moved
        assertThat(Bank.balance(this, s.mule(), s.muleAccount())).isEqualTo("0.00");
        assertThat(get("/api/v1/notifications", s.victim().accessToken()).body()).contains("TRANSFER_UNDER_REVIEW");

        TestUsers.Session admin = Bank.admin(this);
        assertThat(get("/api/v1/admin/fraud-cases", s.victim().accessToken()).status()).isEqualTo(403);
        JsonNode page = get("/api/v1/admin/fraud-cases?status=OPEN&size=100", admin.accessToken()).jsonBody();
        JsonNode listed = null;
        for (JsonNode c : page.get("content")) {
            if (c.get("transferId").asString().equals(s.transfer().get("id").asString())) {
                listed = c;
            }
        }
        assertThat(listed).isNotNull();
        assertThat(listed.get("score").asInt()).isGreaterThanOrEqualTo(60);
        assertThat(listed.get("amount").asString()).isEqualTo("30000.00");
        assertThat(listed.get("toAccountNumberMasked").asString()).startsWith("••••").hasSize(8);
        assertThat(listed.get("topSignal").asString()).isEqualTo("Large amount to someone never paid before");
        String caseId = listed.get("id").asString();

        JsonNode detail = get("/api/v1/admin/fraud-cases/" + caseId, admin.accessToken()).jsonBody();
        assertThat(detail.get("memo").isNull()).isTrue();
        assertThat(detail.get("signals").toString()).contains("NEW_PAYEE_LARGE").contains("UNUSUAL_AMOUNT");
        assertThat(detail.get("customerBaseline").get("avgTransfer").asString()).isEqualTo("1000.00");
        assertThat(detail.get("customerBaseline").get("transfers30d").asInt()).isEqualTo(3);
        assertThat(detail.get("customerBaseline").get("usualHoursIst").asString()).matches("\\d{2}:00–\\d{2}:00");

        // Needs a key and a real reason.
        assertThat(postJson("/api/v1/admin/fraud-cases/" + caseId + "/release", Map.of("reason", "Customer confirmed"),
                Bank.auth(admin)).status()).isEqualTo(400);
        assertThat(decide(admin, caseId, "release", "ok", UUID.randomUUID()).status()).isEqualTo(400);

        UUID key = UUID.randomUUID();
        Response released = decide(admin, caseId, "release", "Customer confirmed by phone.", key);
        assertThat(released.status()).isEqualTo(200);
        assertThat(released.jsonBody().get("status").asString()).isEqualTo("RELEASED");
        assertThat(released.jsonBody().get("decisionReason").asString()).isEqualTo("Customer confirmed by phone.");
        assertThat(decide(admin, caseId, "release", "Customer confirmed by phone.", key).header("Idempotent-Replayed"))
                .isEqualTo("true");
        assertThat(decide(admin, caseId, "reject", "Changed my mind here", UUID.randomUUID()).status()).isEqualTo(409);

        assertAccount(s.victim(), s.from(), "17000.00", "0.00", "17000.00");
        assertThat(Bank.balance(this, s.mule(), s.muleAccount())).isEqualTo("30000.00");
        JsonNode transfers = get("/api/v1/transfers", s.victim().accessToken()).jsonBody().get("content");
        assertThat(transfers.get(0).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(get("/api/v1/notifications", s.mule().accessToken()).body()).contains("MONEY_RECEIVED");
        assertThat(ledger.reconcile().balanced()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'FRAUD_RELEASED' AND target_id = ?",
                Long.class, caseId)).isEqualTo(1);
    }

    @Test
    void aRejectedCaseNeverMovesTheMoney() {
        Scenario s = scam();
        TestUsers.Session admin = Bank.admin(this);
        String caseId = jdbc.queryForObject("SELECT id FROM fraud_cases WHERE transfer_id = ?", UUID.class,
                UUID.fromString(s.transfer().get("id").asString())).toString();

        Response rejected = decide(admin, caseId, "reject", "Customer did not recognise the payee.", UUID.randomUUID());
        assertThat(rejected.jsonBody().get("status").asString()).isEqualTo("REJECTED");
        assertAccount(s.victim(), s.from(), "47000.00", "0.00", "47000.00");
        assertThat(Bank.balance(this, s.mule(), s.muleAccount())).isEqualTo("0.00");
        assertThat(get("/api/v1/transfers", s.victim().accessToken()).jsonBody().get("content").get(0).get("status")
                .asString()).isEqualTo("REJECTED");
        assertThat(get("/api/v1/notifications", s.victim().accessToken()).body()).contains("Transfer stopped");
        assertThat(decide(admin, caseId, "release", "Too late to release this", UUID.randomUUID()).status()).isEqualTo(409);
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void everydayPaymentsAreNotHeld() {
        TestUsers.Session a = TestUsers.registerAndLogin(this);
        TestUsers.Session b = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, a);
        Bank.deposit(this, a, from, "5000.00");
        Response res = Bank.transfer(this, a, from, Bank.firstAccountNumber(this, b), "750.00", UUID.randomUUID());
        assertThat(res.jsonBody().get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void aBurstOfCardAttemptsThenALargeFirstPaymentIsDeclinedAsFraud() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'cards'");
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "50000.00");
        String cardId = postJson("/api/v1/accounts/" + acc + "/cards", "", Bank.auth(s)).jsonBody().get("id").asString();
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "CARD_REVEAL"));
        JsonNode card = postJson("/api/v1/cards/" + cardId + "/reveal", "", h).jsonBody();
        String pan = card.get("pan").asString().replace(" ", "");
        for (int i = 0; i < 4; i++) { // wrong expiry: always declined, but every attempt counts toward velocity
            authorize(s, pan, "01/29", card.get("cvv").asString(), "100.00");
        }
        JsonNode res = authorize(s, pan, card.get("expiry").asString(), card.get("cvv").asString(), "20000.00").jsonBody();
        assertThat(res.get("status").asString()).isEqualTo("DECLINED");
        assertThat(res.get("reason").asString()).isEqualTo("Suspected fraud");
        assertAccount(s, acc, "50000.00", "0.00", "50000.00");
    }

    // ------------------------------------------------------------------ loan documents

    @Test
    void loanDocumentsAreSniffedCappedAndOwnerOnly() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        Response loan = postJson("/api/v1/loans", Map.of("amount", "50000.00", "tenureMonths", 12, "monthlyIncome",
                "40000.00", "purpose", "Home renovation", "disburseAccountId", Bank.firstAccountId(this, s).toString()),
                Bank.auth(s));
        assertThat(loan.status()).isEqualTo(201);
        String loanId = loan.jsonBody().get("id").asString();
        byte[] pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);

        Response noConsent = upload(s, loanId, "slip.pdf", pdf, false);
        assertThat(noConsent.status()).isEqualTo(400);
        assertThat(noConsent.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("consent");
        Response exe = upload(s, loanId, "salary.pdf", "MZ\u0090\u0000 this is not a pdf".getBytes(StandardCharsets.ISO_8859_1), true);
        assertThat(exe.status()).isEqualTo(400);
        assertThat(upload(other, loanId, "slip.pdf", pdf, true).status()).isEqualTo(404);

        Response ok = upload(s, loanId, "../../etc/Salary slip <Aug>.pdf", pdf, true);
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.jsonBody().get("fileName").asString()).isEqualTo("Salary slip _Aug_.pdf");
        assertThat(ok.jsonBody().get("contentType").asString()).isEqualTo("application/pdf");
        assertThat(ok.jsonBody().get("sizeBytes").asLong()).isEqualTo(pdf.length);
        String key = jdbc.queryForObject("SELECT storage_key FROM loan_documents WHERE id = ?", String.class,
                UUID.fromString(ok.jsonBody().get("id").asString()));
        assertThat(Files.readAllBytes(Path.of("target/test-loan-documents", key))).isEqualTo(pdf);

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0};
        assertThat(upload(s, loanId, "a.png", png, true).status()).isEqualTo(201);
        assertThat(upload(s, loanId, "b.jpg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0}, true).status())
                .isEqualTo(201);
        Response fourth = upload(s, loanId, "c.pdf", pdf, true);
        assertThat(fourth.status()).isEqualTo(422);
        assertThat(fourth.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");

        assertThat(get("/api/v1/loans/" + loanId + "/documents", s.accessToken()).jsonBody().size()).isEqualTo(3);
        assertThat(get("/api/v1/loans/" + loanId + "/documents", other.accessToken()).status()).isEqualTo(404);
        TestUsers.Session admin = Bank.admin(this);
        assertThat(get("/api/v1/loans/" + loanId + "/documents", admin.accessToken()).jsonBody().size()).isEqualTo(3);
        JsonNode uw = get("/api/v1/admin/loans/" + loanId + "/underwriting", admin.accessToken()).jsonBody();
        assertThat(uw.get("status").asString()).isEqualTo("PENDING");
        assertThat(uw.get("flags").toString()).contains("Waiting for the Underwriting Analyst.");
        assertThat(get("/api/v1/admin/loans/" + loanId + "/underwriting", s.accessToken()).status()).isEqualTo(403);
    }

    @Test
    void anOversizedDocumentIsRefused() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String loanId = postJson("/api/v1/loans", Map.of("amount", "50000.00", "tenureMonths", 12, "monthlyIncome",
                "40000.00", "purpose", "Car", "disburseAccountId", Bank.firstAccountId(this, s).toString()),
                Bank.auth(s)).jsonBody().get("id").asString();
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = 0x25;
        big[1] = 0x50;
        big[2] = 0x44;
        big[3] = 0x46;
        Response res = upload(s, loanId, "big.pdf", big, true);
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("file");
    }

    // ------------------------------------------------------------------ helpers

    private Response decide(TestUsers.Session admin, String caseId, String decision, String reason, UUID key) {
        return postJson("/api/v1/admin/fraud-cases/" + caseId + "/" + decision, Map.of("reason", reason),
                Bank.authWithKey(admin, key));
    }

    private Response authorize(TestUsers.Session s, String pan, String expiry, String cvv, String amount) {
        return postJson("/api/v1/sandbox/merchant/authorize", Map.of("cardNumber", pan, "expiry", expiry, "cvv", cvv,
                "amount", amount, "merchant", "QuantGen Store", "category", "ELECTRONICS"),
                Bank.authWithKey(s, UUID.randomUUID()));
    }

    private void assertAccount(TestUsers.Session s, UUID acc, String balance, String holds, String available) {
        JsonNode dto = get("/api/v1/accounts/" + acc, s.accessToken()).jsonBody();
        assertThat(dto.get("balance").asString()).isEqualTo(balance);
        assertThat(dto.get("holdsTotal").asString()).isEqualTo(holds);
        assertThat(dto.get("availableBalance").asString()).isEqualTo(available);
    }

    private Response upload(TestUsers.Session s, String loanId, String fileName, byte[] content, boolean consent) {
        String boundary = "qg" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"consent\"\r\n\r\n" + consent + "\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send(request("/api/v1/loans/" + loanId + "/documents")
                .header("Authorization", "Bearer " + s.accessToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
    }
}
