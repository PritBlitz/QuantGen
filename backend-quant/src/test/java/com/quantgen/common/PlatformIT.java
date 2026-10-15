package com.quantgen.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/** M0: context, Flyway schema and DB guards, health, docs, error body and request id. */
class PlatformIT extends IntegrationTest {

    private static final UUID BANK_CASH = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INTEREST_INCOME = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Autowired
    TransactionTemplate tx;

    @Test
    void flywaySeedsSystemAccountsAndInstruments() {
        assertThat(jdbc.queryForList("SELECT code FROM ledger_accounts WHERE kind = 'SYSTEM' ORDER BY code", String.class))
                .containsExactly("BANK_CASH", "BROKERAGE_CLEARING", "INTEREST_INCOME", "LOAN_RECEIVABLE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instruments", Integer.class)).isEqualTo(15);
    }

    @Test
    void healthIsUpAndPublic() {
        Response res = get("/actuator/health");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.jsonBody().get("status").asString()).isEqualTo("UP");
    }

    @Test
    void openApiAndSwaggerArePublic() {
        assertThat(get("/v3/api-docs").status()).isEqualTo(200);
        Response swagger = send(request("/swagger-ui.html").GET().build());
        assertThat(swagger.status()).isIn(200, 302);
    }

    @Test
    void protectedEndpointReturnsStandardUnauthorizedBody() {
        Response res = get("/api/v1/accounts");
        assertThat(res.status()).isEqualTo(401);
        JsonNode body = res.jsonBody();
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("path").asString()).isEqualTo("/api/v1/accounts");
        assertThat(body.get("timestamp").asString()).endsWith("Z");
        assertThat(body.get("fieldErrors").isArray()).isTrue();
        assertThat(body.get("requestId").asString()).isEqualTo(res.header("X-Request-Id"));
        assertThat(res.body()).doesNotContain("Exception", "at com.");
    }

    @Test
    void requestIdIsEchoedWhenSafeAndReplacedWhenNot() {
        Response echoed = send(request("/actuator/health").header("X-Request-Id", "test-req-12345").GET().build());
        assertThat(echoed.header("X-Request-Id")).isEqualTo("test-req-12345");

        Response generated = get("/actuator/health");
        assertThat(UUID.fromString(generated.header("X-Request-Id"))).isNotNull();

        Response unsafe = send(request("/actuator/health").header("X-Request-Id", "bad id with spaces").GET().build());
        assertThat(unsafe.header("X-Request-Id")).isNotEqualTo("bad id with spaces");
    }

    @Test
    void unknownPublicPathReturnsStandardNotFoundBody() {
        Response res = postJson("/api/v1/auth/does-not-exist", "{}", Map.of());
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void ledgerRowsAreInsertOnly() {
        UUID entryId = postBalancedEntry("100.0000");

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET description = 'x' WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_lines WHERE journal_entry_id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("insert-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE ledger_lines CASCADE"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void unbalancedEntryIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_entries (id, type, description) VALUES (?, 'DEPOSIT', 'unbalanced')", entryId);
            jdbc.update("INSERT INTO ledger_lines (journal_entry_id, ledger_account_id, direction, amount, balance_after) "
                    + "VALUES (?, ?, 'DEBIT', 10, 0)", entryId, BANK_CASH);
            jdbc.update("INSERT INTO ledger_lines (journal_entry_id, ledger_account_id, direction, amount, balance_after) "
                    + "VALUES (?, ?, 'CREDIT', 9, 0)", entryId, INTEREST_INCOME);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("unbalanced");
    }

    @Test
    void entryWithSingleLineIsRejectedAtCommit() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_entries (id, type, description) VALUES (?, 'DEPOSIT', 'one line')", entryId);
            jdbc.update("INSERT INTO ledger_lines (journal_entry_id, ledger_account_id, direction, amount, balance_after) "
                    + "VALUES (?, ?, 'DEBIT', 10, 0)", entryId, BANK_CASH);
        })).isInstanceOf(DataAccessException.class).hasMessageContaining("unbalanced");
    }

    @Test
    void customerBalanceCannotGoNegativeAtDbLevel() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_accounts (id, kind, normal_side) VALUES (?, 'CUSTOMER', 'CREDIT')", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_accounts SET balance = -0.0001 WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    /** Inserts a balanced system-to-system entry directly (raw SQL, bypassing balances) for guard tests. */
    private UUID postBalancedEntry(String amount) {
        UUID entryId = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO journal_entries (id, type, description) VALUES (?, 'DEPOSIT', 'guard test')", entryId);
            jdbc.update("INSERT INTO ledger_lines (journal_entry_id, ledger_account_id, direction, amount, balance_after) "
                    + "VALUES (?, ?, 'DEBIT', ?::numeric, 0)", entryId, BANK_CASH, amount);
            jdbc.update("INSERT INTO ledger_lines (journal_entry_id, ledger_account_id, direction, amount, balance_after) "
                    + "VALUES (?, ?, 'CREDIT', ?::numeric, 0)", entryId, INTEREST_INCOME, amount);
        });
        return entryId;
    }
}
