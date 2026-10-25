package com.quantgen.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.support.Bank;
import com.quantgen.support.FakeAgent;
import com.quantgen.support.FakeAgent.Answer;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** F-RISK-5 Underwriting Analyst: masked PDF text in, a checked advisory memo out; the loan is never decided. */
class UnderwritingIT extends IntegrationTest {

    @Autowired
    UnderwritingWorker worker;

    @AfterEach
    void aiOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'ai'");
    }

    @Test
    void theAnalystReadsMaskedPdfTextAndItsAnswerIsCheckedNotTrusted() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String loanId = apply(s, "60000.00");
        String pdfId = upload(s, loanId, "slip.pdf", salarySlip()).get("id").asString();
        upload(s, loanId, "statement.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0});
        onlyThisLoanQueued(loanId);
        TestUsers.Session admin = Bank.admin(this);

        try (FakeAgent agent = new FakeAgent()) {
            assertThat(worker.runOnce()).isZero(); // the ai flag is off
            jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'ai'");
            assertThat(worker.runOnce()).isZero(); // uploads just happened: wait for the customer to finish
            settle(loanId);

            agent.onUnderwritingMemo(req -> Answer.json("""
                    {"memo": {"memo": "Net pay on the slip is well below the declared income.",
                      "extracted": [
                        {"field": "MONTHLY_NET_INCOME", "value": "Rs. 38,500.00", "documentId": "%s", "page": 1},
                        {"field": "EMPLOYER", "value": "Totally Real Bank Ltd", "documentId": "%s", "page": 1},
                        {"field": "PASSWORD", "value": "hunter2", "documentId": "%s", "page": 1}],
                      "flags": ["Only one month of salary shown."],
                      "recommendation": "APPROVE"},
                     "steps": [{"agent": "UNDERWRITING_ANALYST", "kind": "MODEL", "name": "gemini", "status": "OK"}],
                     "usage": {"inputTokens": 900, "outputTokens": 120}}
                    """.formatted(pdfId, pdfId, pdfId)));
            assertThat(worker.runOnce()).isEqualTo(1);

            String facts = agent.lastMemoRequest().toString();
            assertThat(facts).contains("Net pay").contains("60000.00") // page text and the declared income
                    .contains("••••9012").doesNotContain("123456789012") // account number masked
                    .doesNotContain("ABCDE1234F").contains("[PAN]")
                    .doesNotContain("asha@example.com")
                    .doesNotContain("statement.png"); // images never reach the model
        }

        JsonNode uw = get("/api/v1/admin/loans/" + loanId + "/underwriting", admin.accessToken()).jsonBody();
        assertThat(uw.get("status").asString()).isEqualTo("READY");
        assertThat(uw.get("memo").asString()).contains("below the declared income");
        assertThat(uw.get("extracted").size()).isEqualTo(1); // the made-up employer and the bad field are dropped
        assertThat(uw.get("extracted").get(0).get("field").asString()).isEqualTo("MONTHLY_NET_INCOME");
        assertThat(uw.get("extracted").get(0).get("page").asInt()).isEqualTo(1);
        assertThat(uw.get("extracted").get(0).get("documentId").asString()).isEqualTo(pdfId);
        String flags = uw.get("flags").toString();
        assertThat(flags).contains("statement.png is an image")
                .contains("2 values the Analyst cited weren't on the cited page")
                .contains("Declared income ₹60000.00 is more than 20% above the documented net income ₹38500.00")
                .contains("Only one month of salary shown.");
        assertThat(uw.get("recommendation").asString()).isEqualTo("REVIEW"); // the core won't let APPROVE stand
        assertThat(uw.get("generatedAt").isNull()).isFalse();
        String runId = uw.get("runId").asString();
        assertThat(get("/api/v1/admin/ai/runs/" + runId, admin.accessToken()).jsonBody().get("steps").get(0)
                .get("agent").asString()).isEqualTo("UNDERWRITING_ANALYST");
        assertThat(get("/api/v1/loans/" + loanId, s.accessToken()).jsonBody().get("status").asString())
                .isEqualTo("PENDING"); // advice only

        // A new document makes the memo stale: back to waiting.
        upload(s, loanId, "slip2.pdf", salarySlip());
        uw = get("/api/v1/admin/loans/" + loanId + "/underwriting", admin.accessToken()).jsonBody();
        assertThat(uw.get("status").asString()).isEqualTo("PENDING");
        assertThat(uw.get("flags").toString()).contains("Waiting for the Underwriting Analyst.");
    }

    @Test
    void aMemoThatKeepsFailingIsMarkedFailedAfterThreeTries() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String loanId = apply(s, "40000.00");
        upload(s, loanId, "slip.pdf", salarySlip());
        onlyThisLoanQueued(loanId);
        try (FakeAgent agent = new FakeAgent()) {
            jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'ai'");
            agent.onUnderwritingMemo(req -> Answer.json("{\"memo\": {\"memo\": \"ok\", \"recommendation\": \"YES\"}}"));
            for (int i = 0; i < 4; i++) {
                settle(loanId);
                worker.runOnce();
            }
        }
        JsonNode uw = get("/api/v1/admin/loans/" + loanId + "/underwriting", Bank.admin(this).accessToken()).jsonBody();
        assertThat(uw.get("status").asString()).isEqualTo("FAILED");
        assertThat(uw.get("flags").toString()).contains("review the documents yourself");
        assertThat(uw.get("recommendation").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM underwriting_memos WHERE loan_id = ?::uuid", Integer.class,
                loanId)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ helpers

    private String apply(TestUsers.Session s, String income) {
        return postJson("/api/v1/loans", Map.of("amount", "50000.00", "tenureMonths", 12, "monthlyIncome", income,
                "purpose", "Home renovation", "disburseAccountId", Bank.firstAccountId(this, s).toString()),
                Bank.auth(s)).jsonBody().get("id").asString();
    }

    /** Other tests' queued memos stay out of the way of this one. */
    private void onlyThisLoanQueued(String loanId) {
        jdbc.update("UPDATE underwriting_memos SET status = 'FAILED' WHERE loan_id <> ?::uuid AND status = 'PENDING'",
                loanId);
    }

    /** As if the last upload was a minute ago and no claim is in flight. */
    private void settle(String loanId) {
        jdbc.update("UPDATE underwriting_memos SET requested_at = now() - interval '1 minute', claimed_at = NULL "
                + "WHERE loan_id = ?::uuid", loanId);
    }

    private static byte[] salarySlip() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
                c.beginText();
                c.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                c.newLineAtOffset(50, 700);
                c.setLeading(16);
                for (String line : new String[] {"Salary slip - September 2026", "Employer: Acme Widgets Pvt Ltd",
                        "PAN: ABCDE1234F  Email: asha@example.com", "Salary account: 123456789012",
                        "Gross pay: Rs. 45,000.00", "Net pay: Rs. 38,500.00"}) {
                    c.showText(line);
                    c.newLine();
                }
                c.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    private JsonNode upload(TestUsers.Session s, String loanId, String fileName, byte[] content) {
        String boundary = "qg" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"consent\"\r\n\r\ntrue\r\n"
                + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n";
        body.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Response res = send(request("/api/v1/loans/" + loanId + "/documents")
                .header("Authorization", "Bearer " + s.accessToken())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build());
        assertThat(res.status()).isEqualTo(201);
        return res.jsonBody();
    }
}
