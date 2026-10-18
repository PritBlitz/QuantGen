package com.quantgen.support;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * A minimal software WebAuthn authenticator (ES256, "none" attestation) for tests: it turns server-issued options
 * into the credential JSON a browser would send, so the real Yubico verification runs end to end. Not for
 * production use; it exists only so passkey flows can be tested without a browser.
 */
public final class SoftAuthenticator {

    private final KeyPair keyPair;
    private final byte[] credentialId = new byte[32];
    private final String origin;
    private byte[] userHandle;
    public long signCount;

    public SoftAuthenticator(String origin) {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = gen.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        new SecureRandom().nextBytes(credentialId);
        this.origin = origin;
    }

    public String credentialIdB64() {
        return b64(credentialId);
    }

    /** Answers {@code navigator.credentials.create()} for the given creation options. */
    public Map<String, Object> create(JsonNode options) {
        String challenge = options.get("challenge").asString();
        String rpId = options.get("rp").get("id").asString();
        this.userHandle = Base64.getUrlDecoder().decode(options.get("user").get("id").asString());

        byte[] cose = coseKey();
        ByteBuffer authData = ByteBuffer.allocate(32 + 1 + 4 + 16 + 2 + credentialId.length + cose.length);
        authData.put(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        authData.put((byte) 0x45); // user present + user verified + attested credential data
        authData.putInt((int) signCount);
        authData.put(new byte[16]); // AAGUID
        authData.putShort((short) credentialId.length);
        authData.put(credentialId);
        authData.put(cose);

        ByteArrayOutputStream att = new ByteArrayOutputStream();
        att.write(0xA3);
        text(att, "fmt");
        text(att, "none");
        text(att, "attStmt");
        att.write(0xA0);
        text(att, "authData");
        bytes(att, authData.array());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", b64(clientData("webauthn.create", challenge)));
        response.put("attestationObject", b64(att.toByteArray()));
        response.put("transports", List.of("internal"));
        return credential(response);
    }

    /** Answers {@code navigator.credentials.get()} for the given request options (discoverable or not). */
    public Map<String, Object> get(JsonNode options) {
        String challenge = options.get("challenge").asString();
        String rpId = options.get("rpId").asString();
        byte[] clientData = clientData("webauthn.get", challenge);

        ByteBuffer authData = ByteBuffer.allocate(32 + 1 + 4);
        authData.put(sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        authData.put((byte) 0x05); // user present + user verified
        authData.putInt((int) signCount);

        byte[] signed = new byte[authData.capacity() + 32];
        System.arraycopy(authData.array(), 0, signed, 0, authData.capacity());
        System.arraycopy(sha256(clientData), 0, signed, authData.capacity(), 32);
        byte[] signature;
        try {
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initSign(keyPair.getPrivate());
            sig.update(signed);
            signature = sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clientDataJSON", b64(clientData));
        response.put("authenticatorData", b64(authData.array()));
        response.put("signature", b64(signature));
        if (userHandle != null) {
            response.put("userHandle", b64(userHandle));
        }
        return credential(response);
    }

    // ------------------------------------------------------------------ encoding

    private Map<String, Object> credential(Map<String, Object> response) {
        Map<String, Object> cred = new LinkedHashMap<>();
        cred.put("id", b64(credentialId));
        cred.put("rawId", b64(credentialId));
        cred.put("type", "public-key");
        cred.put("response", response);
        cred.put("clientExtensionResults", Map.of());
        return cred;
    }

    private byte[] clientData(String type, String challenge) {
        return ("{\"type\":\"" + type + "\",\"challenge\":\"" + challenge + "\",\"origin\":\"" + origin
                + "\",\"crossOrigin\":false}").getBytes(StandardCharsets.UTF_8);
    }

    /** COSE_Key for ES256: {1: 2, 3: -7, -1: 1, -2: x, -3: y}. */
    private byte[] coseKey() {
        ECPublicKey pub = (ECPublicKey) keyPair.getPublic();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xA5);
        out.write(0x01);
        out.write(0x02);
        out.write(0x03);
        out.write(0x26);
        out.write(0x20);
        out.write(0x01);
        out.write(0x21);
        bytes(out, fixed32(pub.getW().getAffineX()));
        out.write(0x22);
        bytes(out, fixed32(pub.getW().getAffineY()));
        return out.toByteArray();
    }

    private static byte[] fixed32(BigInteger n) {
        byte[] raw = n.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }

    /** CBOR byte string header + content (major type 2). */
    private static void bytes(ByteArrayOutputStream out, byte[] data) {
        header(out, 0x40, data.length);
        out.writeBytes(data);
    }

    /** CBOR text string (major type 3). */
    private static void text(ByteArrayOutputStream out, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        header(out, 0x60, data.length);
        out.writeBytes(data);
    }

    private static void header(ByteArrayOutputStream out, int major, int len) {
        if (len < 24) {
            out.write(major | len);
        } else if (len < 256) {
            out.write(major | 24);
            out.write(len);
        } else {
            out.write(major | 25);
            out.write(len >> 8);
            out.write(len & 0xFF);
        }
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] in) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(in);
    }
}
