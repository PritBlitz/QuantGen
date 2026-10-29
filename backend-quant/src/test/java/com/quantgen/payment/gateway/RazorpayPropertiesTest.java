package com.quantgen.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** D-036: top-ups run in Razorpay test mode only; a live key must never get the app started. */
class RazorpayPropertiesTest {

    @Test
    void onlyTestModeKeysAreAccepted() {
        assertThat(new RazorpayProperties("rzp_test_abc", "s", "w", null).apiConfigured()).isTrue();
        assertThat(new RazorpayProperties("", "", "", null).apiConfigured()).isFalse(); // unset: top-ups off (503)
        assertThatThrownBy(() -> new RazorpayProperties("rzp_live_abc123", "secret", "w", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("rzp_live_abc123").hasMessageNotContaining("secret");
    }
}
