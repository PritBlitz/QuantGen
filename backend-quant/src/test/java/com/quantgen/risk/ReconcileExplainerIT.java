package com.quantgen.risk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.support.Bank;
import com.quantgen.support.FakeAgent;
import com.quantgen.support.FakeAgent.Answer;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** F-RISK-6: a reconcile mismatch is queued, explained from its entries, and the explanation only names those. */
class ReconcileExplainerIT extends IntegrationTest {

    @Autowired
    ReconcileExplainer explainer;

    @Test
    void aMismatchGetsAnAdvisoryExplanationThatOnlyNamesEntriesItWasShown() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "40.00");
        UUID entry = jdbc.queryForObject("SELECT journal_entry_id FROM ledger_lines WHERE ledger_account_id = ? LIMIT 1",
                UUID.class, acc);
        TestUsers.Session admin = Bank.admin(this);
        jdbc.update("UPDATE reconcile_explanations SET status = 'FAILED' WHERE status = 'PENDING'");

        // Someone changes a cached balance outside a posting.
        jdbc.update("UPDATE ledger_accounts SET balance = balance + 5 WHERE id = ?", acc);
        try {
            JsonNode report = get("/api/v1/admin/ledger/reconcile", admin.accessToken()).jsonBody();
            assertThat(report.get("balanced").asBoolean()).isFalse();
            JsonNode mismatch = report.get("mismatches").get(0);
            assertThat(mismatch.get("ledgerAccountId").asString()).isEqualTo(acc.toString());
            assertThat(mismatch.get("explanation").isNull()).isTrue(); // queued, not written yet

            try (FakeAgent agent = new FakeAgent()) {
                jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'ai'");
                assertThat(explainer.runOnce()).isZero();
                jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'ai'");
                agent.onReconcileExplanation(req -> Answer.json("{\"memo\":{\"summary\":\"The cached balance is ₹5.00 above "
                        + "its lines; no entry explains it, so it was likely changed outside a posting.\",\"likelyCause\":"
                        + "\"Balance edited outside a posting\",\"affectedEntries\":[\"" + entry + "\",\""
                        + UUID.randomUUID() + "\",\"DROP TABLE\"]},\"steps\":[{\"agent\":\"RECONCILE_EXPLAINER\","
                        + "\"kind\":\"MODEL\",\"name\":\"gemini\",\"status\":\"OK\"}]}"));
                assertThat(explainer.runOnce()).isEqualTo(1);
                String facts = agent.lastMemoRequest().toString();
                assertThat(facts).contains("\"difference\":\"5.00\"").contains(entry.toString());
            } finally {
                jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'ai'");
            }

            JsonNode explained = get("/api/v1/admin/ledger/reconcile", admin.accessToken()).jsonBody()
                    .get("mismatches").get(0).get("explanation");
            assertThat(explained.get("likelyCause").asString()).isEqualTo("Balance edited outside a posting");
            assertThat(explained.get("affectedEntries").size()).isEqualTo(1); // only the entry it was shown
            assertThat(explained.get("affectedEntries").get(0).asString()).isEqualTo(entry.toString());
            assertThat(get("/api/v1/admin/ai/runs/" + explained.get("runId").asString(), admin.accessToken()).jsonBody()
                    .get("steps").get(0).get("agent").asString()).isEqualTo("RECONCILE_EXPLAINER");
            assertThat(get("/api/v1/admin/ledger/reconcile", s.accessToken()).status()).isEqualTo(403);
        } finally {
            jdbc.update("UPDATE ledger_accounts SET balance = balance - 5 WHERE id = ?", acc);
        }
        assertThat(get("/api/v1/admin/ledger/reconcile", admin.accessToken()).jsonBody().get("balanced").asBoolean())
                .isTrue();
    }
}
