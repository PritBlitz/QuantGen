package com.quantgen.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** M5: PRD §7.8 notifications: list, unread count, mark read, read-all, ownership, 50 cap. */
class NotificationIT extends IntegrationTest {

    private void moneyReceived(TestUsers.Session from, UUID fromAccount, TestUsers.Session to, int times) {
        String toNumber = Bank.firstAccountNumber(this, to);
        for (int i = 0; i < times; i++) {
            assertThat(Bank.transfer(this, from, fromAccount, toNumber, "1.00", UUID.randomUUID()).status())
                    .isEqualTo(201);
        }
    }

    @Test
    void listCountMarkReadAndReadAll() {
        TestUsers.Session sender = TestUsers.registerAndLogin(this);
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID senderAccount = Bank.firstAccountId(this, sender);
        Bank.deposit(this, sender, senderAccount, "100.00");
        moneyReceived(sender, senderAccount, s, 3);

        assertThat(get("/api/v1/notifications/unread-count", s.accessToken()).jsonBody().get("count").asLong())
                .isEqualTo(3);
        JsonNode list = get("/api/v1/notifications", s.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(3);
        JsonNode first = list.get(0);
        assertThat(first.get("type").asString()).isEqualTo("MONEY_RECEIVED");
        assertThat(first.get("read").asBoolean()).isFalse();
        assertThat(first.get("title").asString()).isNotBlank();
        assertThat(first.get("createdAt").asString()).endsWith("Z");

        String id = first.get("id").asString();
        assertThat(patch("/api/v1/notifications/" + id + "/read", s.accessToken()).status()).isEqualTo(204);
        assertThat(patch("/api/v1/notifications/" + id + "/read", s.accessToken()).status()).isEqualTo(204);
        assertThat(get("/api/v1/notifications/unread-count", s.accessToken()).jsonBody().get("count").asLong())
                .isEqualTo(2);
        assertThat(get("/api/v1/notifications?unreadOnly=true", s.accessToken()).jsonBody().size()).isEqualTo(2);

        assertThat(postJson("/api/v1/notifications/read-all", "", Bank.auth(s)).status()).isEqualTo(204);
        assertThat(get("/api/v1/notifications/unread-count", s.accessToken()).jsonBody().get("count").asLong())
                .isZero();
        assertThat(get("/api/v1/notifications", s.accessToken()).jsonBody().get(2).get("read").asBoolean()).isTrue();
    }

    @Test
    void anotherUsersNotificationIsNotFound() {
        TestUsers.Session sender = TestUsers.registerAndLogin(this);
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session snoop = TestUsers.registerAndLogin(this);
        UUID senderAccount = Bank.firstAccountId(this, sender);
        Bank.deposit(this, sender, senderAccount, "10.00");
        moneyReceived(sender, senderAccount, s, 1);
        String id = get("/api/v1/notifications", s.accessToken()).jsonBody().get(0).get("id").asString();

        Response res = patch("/api/v1/notifications/" + id + "/read", snoop.accessToken());
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(get("/api/v1/notifications", snoop.accessToken()).jsonBody().size()).isZero();
        // snoop's read-all doesn't touch s's notifications
        postJson("/api/v1/notifications/read-all", "", Bank.auth(snoop));
        assertThat(get("/api/v1/notifications/unread-count", s.accessToken()).jsonBody().get("count").asLong())
                .isEqualTo(1);
        assertThat(get("/api/v1/notifications").status()).isEqualTo(401);
    }

    @Test
    void listIsCappedAtFifty() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        for (int i = 0; i < 55; i++) {
            jdbc.update("INSERT INTO notifications (user_id, type, title, body, created_at) "
                    + "VALUES (?, 'EMI_DUE', 'n', 'b', now() - make_interval(secs => ?))", s.userId(), i);
        }
        JsonNode list = get("/api/v1/notifications", s.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(50);
        assertThat(get("/api/v1/notifications/unread-count", s.accessToken()).jsonBody().get("count").asLong())
                .isEqualTo(55);
        // Newest first: the row inserted with the smallest offset comes first.
        assertThat(list.get(0).get("createdAt").asString()).isGreaterThan(list.get(49).get("createdAt").asString());
    }
}
