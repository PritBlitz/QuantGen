package com.quantgen.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PRD 12.12: what a personal access token may reach (deny by default). */
class PatScopeTest {

    @Test
    void readTokensReachOnlyTheReadRoutes() {
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/accounts")).isTrue();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/accounts/1b4e/transactions")).isTrue();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/analytics/summary")).isTrue();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/insights/spending")).isTrue();

        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/accounts/1b4e/statement")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/users/me/tokens")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/users/me/sessions")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/cards")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "GET", "/api/v1/admin/users")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "POST", "/api/v1/transfers")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "POST", "/api/v1/intents")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read", "DELETE", "/api/v1/accounts")).isFalse();
    }

    @Test
    void proposeTokensCanAlsoProposeIntentsButNothingElse() {
        assertThat(PatAuthenticationFilter.allows("read+propose", "POST", "/api/v1/intents")).isTrue();
        assertThat(PatAuthenticationFilter.allows("read+propose", "POST", "/api/v1/intents/1/approve")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read+propose", "POST", "/api/v1/transfers")).isFalse();
        assertThat(PatAuthenticationFilter.allows("read+propose", "GET", "/api/v1/accounts")).isTrue();
    }
}
