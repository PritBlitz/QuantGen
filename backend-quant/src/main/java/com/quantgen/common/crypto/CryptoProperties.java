package com.quantgen.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keys for field-level encryption (D-032): {@code qg.crypto.field-key} encrypts PAN and address,
 * {@code qg.crypto.index-key} keys the blind index used for uniqueness. Both are base64 of 32 random bytes and
 * come from the environment (QG_FIELD_KEY, QG_INDEX_KEY). There are no defaults outside dev/test profiles, and
 * {@link FieldCrypto} refuses to start with a missing or short key.
 */
@ConfigurationProperties(prefix = "qg.crypto")
public record CryptoProperties(String fieldKey, String indexKey, Integer keyVersion) {

    public CryptoProperties {
        keyVersion = keyVersion == null ? 1 : keyVersion;
    }

    @Override
    public String toString() {
        return "CryptoProperties[keys=redacted, keyVersion=" + keyVersion + "]";
    }
}
