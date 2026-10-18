package com.quantgen.common.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class FieldCryptoTest {

    private static final String KEY_A = b64('a');
    private static final String KEY_B = b64('b');

    private static String b64(char c) {
        return Base64.getEncoder().encodeToString(String.valueOf(c).repeat(32).getBytes());
    }

    private static FieldCrypto crypto() {
        return new FieldCrypto(new CryptoProperties(KEY_A, KEY_B, 1));
    }

    @Test
    void roundTripsAndNeverRepeatsACiphertext() {
        FieldCrypto c = crypto();
        String one = c.encrypt("ABCDE1234F", "kyc.pan");
        String two = c.encrypt("ABCDE1234F", "kyc.pan");

        assertThat(one).startsWith("v1.").isNotEqualTo(two).doesNotContain("ABCDE");
        assertThat(c.decrypt(one, "kyc.pan")).isEqualTo("ABCDE1234F");
        assertThat(c.decrypt(two, "kyc.pan")).isEqualTo("ABCDE1234F");
    }

    @Test
    void aCiphertextIsBoundToItsContextAndDetectsTampering() {
        FieldCrypto c = crypto();
        String stored = c.encrypt("12 Long Street", "kyc.address");

        assertThatThrownBy(() -> c.decrypt(stored, "kyc.pan")).isInstanceOf(IllegalStateException.class);
        char last = stored.charAt(stored.length() - 1);
        String tampered = stored.substring(0, stored.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> c.decrypt(tampered, "kyc.address")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt("v2." + stored.substring(3), "kyc.address"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(null, "kyc.address")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKeyCannotDecrypt() {
        String stored = crypto().encrypt("secret", "ctx");
        FieldCrypto other = new FieldCrypto(new CryptoProperties(KEY_B, KEY_A, 1));

        assertThatThrownBy(() -> other.decrypt(stored, "ctx")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blindIndexIsDeterministicPerContextAndKeyed() {
        FieldCrypto c = crypto();

        assertThat(c.blindIndex("ABCDE1234F", "kyc.pan")).isEqualTo(c.blindIndex("ABCDE1234F", "kyc.pan")).hasSize(64);
        assertThat(c.blindIndex("ABCDE1234F", "kyc.pan")).isNotEqualTo(c.blindIndex("ABCDE1234G", "kyc.pan"));
        assertThat(c.blindIndex("ABCDE1234F", "kyc.pan")).isNotEqualTo(c.blindIndex("ABCDE1234F", "card.pan"));
        assertThat(new FieldCrypto(new CryptoProperties(KEY_A, KEY_A, 1)).blindIndex("x", "c"))
                .isNotEqualTo(c.blindIndex("x", "c"));
    }

    @Test
    void refusesMissingShortOrNonBase64Keys() {
        assertThatThrownBy(() -> new FieldCrypto(new CryptoProperties(null, KEY_B, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QG_FIELD_KEY");
        assertThatThrownBy(() -> new FieldCrypto(new CryptoProperties(KEY_A, " ", 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("QG_INDEX_KEY");
        assertThatThrownBy(() -> new FieldCrypto(new CryptoProperties(Base64.getEncoder().encodeToString(new byte[16]), KEY_B, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new FieldCrypto(new CryptoProperties("***", KEY_B, 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
    }

    @Test
    void propertiesNeverPrintKeys() {
        assertThat(new CryptoProperties(KEY_A, KEY_B, 1).toString()).doesNotContain(KEY_A).doesNotContain(KEY_B);
    }
}
