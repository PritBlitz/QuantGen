package com.quantgen.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** M5: admin users, freeze/unfreeze (audited and notified), stats, audit log, role checks. */
class AdminIT extends IntegrationTest {

    @Test
    void everyAdminEndpointRequiresTheAdminRole() {
        TestUsers.Session customer = TestUsers.registerAndLogin(this);
        String[] paths = {"/api/v1/admin/users", "/api/v1/admin/users/" + customer.userId(), "/api/v1/admin/stats",
                "/api/v1/admin/audit-logs", "/api/v1/admin/loans", "/api/v1/admin/ledger/reconcile"};
        for (String path : paths) {
            assertThat(get(path).status()).as(path).isEqualTo(401);
            Response forbidden = get(path, customer.accessToken());
            assertThat(forbidden.status()).as(path).isEqualTo(403);
            assertThat(forbidden.jsonBody().get("code").asString()).isEqualTo("FORBIDDEN");
        }
        assertThat(patchJson("/api/v1/admin/accounts/" + Bank.firstAccountId(this, customer) + "/status",
                Map.of("status", "FROZEN", "reason", "Suspicious activity"), Bank.auth(customer)).status())
                .isEqualTo(403);
    }

    @Test
    void userSearchAndDetail() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.Session customer = TestUsers.registerAndLogin(this);
        String local = customer.email().substring(0, customer.email().indexOf('@'));

        JsonNode page = get("/api/v1/admin/users?q=" + local.toUpperCase(), admin.accessToken()).jsonBody();
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("email").asString()).isEqualTo(customer.email());
        assertThat(row.get("accountCount").asLong()).isEqualTo(1);
        assertThat(row.get("role").asString()).isEqualTo("CUSTOMER");
        assertThat(row.has("passwordHash")).isFalse();

        // LIKE wildcards are matched literally.
        assertThat(get("/api/v1/admin/users?q=%25", admin.accessToken()).jsonBody().get("totalElements").asLong())
                .isZero();
        assertThat(get("/api/v1/admin/users?q=" + "x".repeat(101), admin.accessToken()).status()).isEqualTo(400);

        JsonNode all = get("/api/v1/admin/users?size=5", admin.accessToken()).jsonBody();
        assertThat(all.get("size").asInt()).isEqualTo(5);
        assertThat(all.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);

        JsonNode detail = get("/api/v1/admin/users/" + customer.userId(), admin.accessToken()).jsonBody();
        assertThat(detail.get("accounts").size()).isEqualTo(1);
        assertThat(detail.get("accounts").get(0).get("balance").asString()).isEqualTo("0.00");
        assertThat(detail.get("loans").size()).isZero();
        assertThat(get("/api/v1/admin/users/" + UUID.randomUUID(), admin.accessToken()).status()).isEqualTo(404);
    }

    @Test
    void freezeAndUnfreezeAreAuditedAndNotified() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.Session customer = TestUsers.registerAndLogin(this);
        UUID account = Bank.firstAccountId(this, customer);
        String number = Bank.firstAccountNumber(this, customer);
        Bank.deposit(this, customer, account, "1000.00");
        String path = "/api/v1/admin/accounts/" + account + "/status";

        assertThat(patchJson(path, Map.of("status", "FROZEN"), Bank.auth(admin)).status()).isEqualTo(400);
        assertThat(patchJson(path, Map.of("status", "CLOSED", "reason", "Closing it"), Bank.auth(admin)).status())
                .isEqualTo(400);
        assertThat(patchJson("/api/v1/admin/accounts/" + UUID.randomUUID() + "/status",
                Map.of("status", "FROZEN", "reason", "Suspicious activity"), Bank.auth(admin)).status()).isEqualTo(404);

        Response frozen = patchJson(path, Map.of("status", "FROZEN", "reason", "Suspicious activity"), Bank.auth(admin));
        assertThat(frozen.status()).isEqualTo(200);
        assertThat(frozen.jsonBody().get("status").asString()).isEqualTo("FROZEN");
        assertThat(patchJson(path, Map.of("status", "FROZEN", "reason", "Suspicious activity"), Bank.auth(admin))
                .status()).isEqualTo(409);

        // Frozen: can't send, can still receive.
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        Response send = Bank.transfer(this, customer, account, Bank.firstAccountNumber(this, other), "10.00",
                UUID.randomUUID());
        assertThat(send.jsonBody().get("code").asString()).isEqualTo("ACCOUNT_FROZEN");
        UUID otherAccount = Bank.firstAccountId(this, other);
        Bank.deposit(this, other, otherAccount, "100.00");
        assertThat(Bank.transfer(this, other, otherAccount, number, "10.00", UUID.randomUUID()).status()).isEqualTo(201);

        assertThat(patchJson(path, Map.of("status", "ACTIVE", "reason", "Verified with customer"), Bank.auth(admin))
                .jsonBody().get("status").asString()).isEqualTo("ACTIVE");

        // Audit: masked number and reason, actor email resolved, newest first, filterable.
        JsonNode logs = get("/api/v1/admin/audit-logs?action=ACCOUNT_FROZEN&size=100", admin.accessToken()).jsonBody();
        JsonNode entry = null;
        for (JsonNode n : logs.get("content")) {
            if (n.get("targetId").asString().equals(account.toString())) {
                entry = n;
            }
        }
        assertThat(entry).isNotNull();
        assertThat(entry.get("details").asString())
                .isEqualTo("Account ••••" + number.substring(8) + ": Suspicious activity")
                .doesNotContain(number);
        assertThat(entry.get("targetType").asString()).isEqualTo("ACCOUNT");
        assertThat(entry.get("actorId").asString()).isEqualTo(admin.userId().toString());
        assertThat(entry.get("actorEmail").asString()).isEqualTo(admin.email());
        assertThat(entry.get("ip").asString()).isNotBlank();
        assertThat(entry.get("requestId").asString()).isNotBlank();

        JsonNode byActor = get("/api/v1/admin/audit-logs?actor=" + admin.email().substring(0, 10).toUpperCase(),
                admin.accessToken()).jsonBody();
        assertThat(byActor.get("totalElements").asLong()).isGreaterThanOrEqualTo(2);
        for (JsonNode n : byActor.get("content")) {
            assertThat(n.get("actorEmail").asString()).isEqualTo(admin.email());
        }

        // Customer side, newest first: unfrozen, the money received while frozen, frozen.
        JsonNode notes = get("/api/v1/notifications", customer.accessToken()).jsonBody();
        assertThat(notes.size()).isEqualTo(3);
        assertThat(notes.get(0).get("type").asString()).isEqualTo("ACCOUNT_UNFROZEN");
        assertThat(notes.get(1).get("type").asString()).isEqualTo("MONEY_RECEIVED");
        assertThat(notes.get(2).get("type").asString()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(notes.get(2).get("body").asString()).contains("Suspicious activity").doesNotContain(number);
    }

    @Test
    void statsReflectTheBank() {
        TestUsers.Session admin = Bank.admin(this);
        JsonNode before = get("/api/v1/admin/stats", admin.accessToken()).jsonBody();
        assertThat(before.get("totalDeposits").isString()).isTrue();
        assertThat(before.get("outstandingPrincipal").isString()).isTrue();

        TestUsers.Session customer = TestUsers.registerAndLogin(this);
        Bank.deposit(this, customer, Bank.firstAccountId(this, customer), "2500.00");
        JsonNode after = get("/api/v1/admin/stats", admin.accessToken()).jsonBody();
        assertThat(after.get("totalUsers").asLong()).isEqualTo(before.get("totalUsers").asLong() + 1);
        assertThat(new BigDecimal(after.get("totalDeposits").asString())
                .subtract(new BigDecimal(before.get("totalDeposits").asString())))
                .isEqualByComparingTo("2500.00");
        assertThat(after.get("tradesToday").asLong()).isGreaterThanOrEqualTo(0);
    }
}
