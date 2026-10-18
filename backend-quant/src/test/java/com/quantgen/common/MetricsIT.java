package com.quantgen.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

/** V2-M0: /actuator/prometheus exposes the qg.* business metrics. */
// Spring Boot tests switch metrics export off by default.
@TestPropertySource(properties = "management.prometheus.metrics.export.enabled=true")
class MetricsIT extends IntegrationTest {

    @Test
    void prometheusExposesLedgerTransferAndLoginMetrics() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aliceAccount = Bank.firstAccountId(this, alice);
        assertThat(Bank.deposit(this, alice, aliceAccount, "500.00").status()).isEqualTo(201);
        assertThat(Bank.transfer(this, alice, aliceAccount, Bank.firstAccountNumber(this, bob), "100.00",
                UUID.randomUUID()).status()).isEqualTo(201);

        Response res = get("/actuator/prometheus");

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body())
                .contains("qg_ledger_post_seconds_count{")
                .contains("outcome=\"success\"")
                .contains("type=\"TRANSFER\"")
                .contains("qg_transfers_total{")
                .contains("qg_auth_logins_total{");
        // Labels are enums only: no account numbers or ids leak into metric names.
        assertThat(res.body()).doesNotContain(Bank.firstAccountNumber(this, alice));
    }
}
