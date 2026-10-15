package com.quantgen.account.service;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

/** Random 12-digit account numbers (F-ACC-2). Uniqueness is checked by the caller and the DB index. */
@Component
class AccountNumberGenerator {

    private final SecureRandom random = new SecureRandom();

    String next() {
        StringBuilder sb = new StringBuilder(12);
        sb.append(1 + random.nextInt(9)); // no leading zero
        for (int i = 1; i < 12; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
