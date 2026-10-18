package com.quantgen.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM field encryption plus an HMAC-SHA-256 blind index (D-032, ARCHITECTURE section 12.13).
 *
 * <p>Ciphertext format: {@code v<keyVersion>.<base64url(iv || ciphertext || tag)>}. The key version travels
 * with each value so keys can rotate without re-encrypting everything at once. Every value is bound to a
 * {@code context} (for example {@code kyc.pan}) as GCM additional data, so a ciphertext copied into another
 * column or row type fails to decrypt. The IV is random per value (96 bits).
 *
 * <p>The blind index is deterministic per (context, value) so equality can be enforced with a unique index
 * without storing or searching the plaintext. It uses a different key from encryption.
 */
@Component
public class FieldCrypto {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec fieldKey;
    private final SecretKeySpec indexKey;
    private final int version;

    public FieldCrypto(CryptoProperties props) {
        this.fieldKey = new SecretKeySpec(decodeKey(props.fieldKey(), "qg.crypto.field-key (QG_FIELD_KEY)"), "AES");
        this.indexKey = new SecretKeySpec(decodeKey(props.indexKey(), "qg.crypto.index-key (QG_INDEX_KEY)"),
                "HmacSHA256");
        this.version = props.keyVersion();
    }

    public String encrypt(String plaintext, String context) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, fieldKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return "v" + version + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Field encryption failed", e);
        }
    }

    public String decrypt(String stored, String context) {
        try {
            String prefix = "v" + version + ".";
            if (stored == null || !stored.startsWith(prefix)) {
                throw new IllegalStateException("Unknown ciphertext key version");
            }
            byte[] in = Base64.getUrlDecoder().decode(stored.substring(prefix.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, fieldKey, new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // Deliberately vague: never include the value or the key in the message.
            throw new IllegalStateException("Field decryption failed", e);
        }
    }

    /** Hex HMAC of {@code context + 0x00 + value}. Callers normalize the value first (for example upper-case PAN). */
    public String blindIndex(String value, String context) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKey);
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Blind index failed", e);
        }
    }

    private static byte[] decodeKey(String base64, String name) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(name + " must be set");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " must be base64", e);
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(name + " must decode to exactly 32 bytes");
        }
        return bytes;
    }
}
