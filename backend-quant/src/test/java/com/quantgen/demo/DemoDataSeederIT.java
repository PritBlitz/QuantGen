package com.quantgen.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** M6: the demo profile seeds the PRD §10 data through the real services, and re-seeding changes nothing. */
@ActiveProfiles("demo")
class DemoDataSeederIT extends IntegrationTest {

    @Autowired
    DemoDataSeeder seeder;

    @Autowired
    LedgerService ledger;

    private String snapshot(TestUsers.Session s) {
        return get("/api/v1/accounts", s.accessToken()).body() + get("/api/v1/portfolio", s.accessToken())
                .jsonBody().get("holdings").size() + get("/api/v1/loans", s.accessToken()).body();
    }

    @Test
    void demoUsersHaveARealisticBankAndSeedingIsIdempotent() {
        TestUsers.Session demo = TestUsers.login(this, DemoDataSeeder.DEMO_EMAIL, DemoDataSeeder.DEFAULT_USER_PASSWORD);
        TestUsers.Session admin = TestUsers.login(this, DemoDataSeeder.ADMIN_EMAIL, DemoDataSeeder.DEFAULT_ADMIN_PASSWORD);
        assertThat(get("/api/v1/users/me", admin.accessToken()).jsonBody().get("role").asString()).isEqualTo("ADMIN");

        JsonNode accounts = get("/api/v1/accounts", demo.accessToken()).jsonBody();
        assertThat(accounts.size()).isEqualTo(2);
        assertThat(get("/api/v1/portfolio", demo.accessToken()).jsonBody().get("holdings").size()).isEqualTo(5);

        JsonNode loans = get("/api/v1/loans", demo.accessToken()).jsonBody();
        assertThat(loans.size()).isEqualTo(2);
        long active = 0;
        long pending = 0;
        for (JsonNode l : loans) {
            active += "ACTIVE".equals(l.get("status").asString()) ? 1 : 0;
            pending += "PENDING".equals(l.get("status").asString()) ? 1 : 0;
        }
        assertThat(active).isEqualTo(1);
        assertThat(pending).isEqualTo(1);

        JsonNode notifications = get("/api/v1/notifications", demo.accessToken()).jsonBody();
        assertThat(notifications.size()).isGreaterThanOrEqualTo(3); // money received, loan approved, EMI paid
        assertThat(get("/api/v1/market/instruments/QGEN/history?range=1W", demo.accessToken()).jsonBody().size())
                .isGreaterThan(100);
        assertThat(get("/api/v1/admin/loans?status=PENDING", admin.accessToken()).jsonBody().get("totalElements")
                .asLong()).isEqualTo(1);

        String before = snapshot(demo);
        seeder.seed();
        assertThat(snapshot(demo)).isEqualTo(before);
        assertThat(ledger.reconcile().balanced()).isTrue();

        // The peer exists for transfers but nobody can log in with a known password.
        assertThat(postJson("/api/v1/auth/login",
                Map.of("email", DemoDataSeeder.PEER_EMAIL, "password", DemoDataSeeder.DEFAULT_USER_PASSWORD), Map.of())
                .status()).isEqualTo(401);
    }
}
