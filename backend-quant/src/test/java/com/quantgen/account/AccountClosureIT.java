package com.quantgen.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

/** Closing an account: only empty ones with nothing depending on them, with step-up; closed means no money moves. */
class AccountClosureIT extends IntegrationTest {

    private Response close(TestUsers.Session s, UUID id, boolean stepUp) {
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        if (stepUp) {
            h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "SECURITY_SETTINGS"));
        }
        return postJson("/api/v1/accounts/" + id + "/close", "", h);
    }

    @Test
    void onlyAnEmptyAccountWithNothingDependingOnItCloses() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID first = Bank.firstAccountId(this, s);
        UUID second = UUID.fromString(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(s)).jsonBody()
                .get("id").asString());
        Bank.deposit(this, s, second, "250.00");

        assertThat(close(other, second, true).status()).isEqualTo(404);
        assertThat(close(s, second, false).status()).isEqualTo(401); // step-up
        Response notEmpty = close(s, second, true);
        assertThat(notEmpty.status()).isEqualTo(409);
        assertThat(notEmpty.jsonBody().get("message").asString()).contains("move the balance of ₹250.00 out first");

        String secondNumber = jdbc.queryForObject("SELECT account_number FROM accounts WHERE id = ?", String.class, second);
        assertThat(Bank.transfer(this, s, second, Bank.firstAccountNumber(this, s), "250.00", UUID.randomUUID())
                .status()).isEqualTo(201);
        Response closed = close(s, second, true);
        assertThat(closed.status()).as(closed.body()).isEqualTo(200);
        assertThat(closed.jsonBody().get("status").asString()).isEqualTo("CLOSED");
        assertThat(close(s, second, true).status()).isEqualTo(409); // already closed

        // A closed account takes no money in or out.
        Bank.deposit(this, s, first, "100.00");
        assertThat(Bank.transfer(this, s, first, secondNumber, "10.00", UUID.randomUUID()).status())
                .isIn(400, 409, 422);
        assertThat(Bank.balance(this, s, first)).isEqualTo("350.00"); // 250.00 moved out of the closed one + 100.00
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ACCOUNT_CLOSED' AND target_id = ?",
                Long.class, second.toString())).isEqualTo(1);
    }

    @Test
    void whatDependsOnTheAccountIsListed() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        String handle = "c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@qg";
        assertThat(put("/api/v1/users/me/handle", Map.of("handle", handle, "accountId", acc.toString()), s.accessToken())
                .status()).isEqualTo(200);
        Response res = close(s, acc, true);
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.jsonBody().get("message").asString()).contains("point your handle at another account");
        assertThat(get("/api/v1/accounts/" + acc, s.accessToken()).jsonBody().get("status").asString())
                .isEqualTo("ACTIVE");
    }

    @Test
    void anUnpaidTopupBlocksClosingUntilItEnds() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = UUID.fromString(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(s)).jsonBody()
                .get("id").asString());
        UUID topup = UUID.randomUUID();
        jdbc.update("INSERT INTO topups (id, user_id, account_id, amount, gateway_order_id) "
                + "VALUES (?, ?, ?, 500.00, 'order_closure_test')", topup, s.userId(), acc);

        // The customer may be paying right now; closing would leave a captured payment nowhere to land.
        Response blocked = close(s, acc, true);
        assertThat(blocked.status()).isEqualTo(409);
        assertThat(blocked.jsonBody().get("message").asString()).contains("a Razorpay top-up to it isn't finished");

        jdbc.update("UPDATE topups SET status = 'EXPIRED' WHERE id = ?", topup);
        assertThat(close(s, acc, true).status()).isEqualTo(200);
    }
}
