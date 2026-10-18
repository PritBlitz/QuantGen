package com.quantgen.auth.security.passkey;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.domain.User;
import com.quantgen.auth.domain.UserRepository;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.CorsProperties;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-SEC-1 passkeys on top of Yubico's java-webauthn-server (which does the cryptographic checking: origin, RP
 * ID hash, challenge, signature, sign-counter regression, user verification).
 *
 * <p>What this class adds: challenges are stored server-side, single-use and short-lived; every ceremony is
 * bound to its purpose (a registration challenge cannot finish a login) and a step-up challenge is bound to the
 * user and action it was issued for; at most 5 passkeys per user; and a sign-in request never names the account,
 * so {@code /auth/passkeys/login/options} answers identically for everyone and reveals nothing about who has a
 * passkey (discoverable credentials).
 */
@Service
public class PasskeyService {

    public static final int MAX_PASSKEYS = 5;
    private static final Duration CHALLENGE_TTL = Duration.ofMinutes(5);

    /** PRD 12.3 PasskeyDto. */
    public record PasskeyDto(UUID id, String name, Instant createdAt, Instant lastUsedAt) {
    }

    private final PasskeyStore store;
    private final RelyingParty rp;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;
    private final JsonMapper json;

    public PasskeyService(PasskeyStore store, WebAuthnProperties props, CorsProperties cors, UserRepository users,
                          AuditService audit, Clock clock, JsonMapper json) {
        this.store = store;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
        this.rp = RelyingParty.builder()
                .identity(RelyingPartyIdentity.builder().id(props.rpId()).name(props.rpName()).build())
                .credentialRepository(store)
                .origins(new HashSet<>(cors.allowedOrigins()))
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                .build();
    }

    // ------------------------------------------------------------------ registration

    @Transactional
    public JsonNode registrationOptions(UUID userId) {
        if (store.count(userId) >= MAX_PASSKEYS) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can register at most " + MAX_PASSKEYS + " passkeys");
        }
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        PublicKeyCredentialCreationOptions options = rp.startRegistration(StartRegistrationOptions.builder()
                .user(UserIdentity.builder().name(user.getEmail()).displayName(user.getFullName())
                        .id(store.userHandle(userId)).build())
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.PREFERRED)
                        .userVerification(UserVerificationRequirement.PREFERRED).build())
                .timeout(120_000)
                .build());
        try {
            store.saveChallenge(options.getChallenge().getBase64Url(), "REGISTER", userId, null, options.toJson(),
                    clock.instant().plus(CHALLENGE_TTL));
            return json.readTree(options.toCredentialsCreateJson()).get("publicKey");
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialise registration options", e);
        }
    }

    @Transactional(noRollbackFor = ApiException.class)
    public PasskeyDto register(UUID userId, JsonNode credential, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 40 || name.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "name", "must be 1-40 characters");
        }
        PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs> parsed;
        String challenge;
        try {
            parsed = PublicKeyCredential.parseRegistrationResponseJson(credential.toString());
            challenge = parsed.getResponse().getClientData().getChallenge().getBase64Url();
        } catch (IOException | RuntimeException e) {
            throw badCredential();
        }
        PasskeyStore.StoredChallenge stored = store.takeChallenge(challenge, "REGISTER", clock.instant())
                .filter(c -> userId.equals(c.userId()))
                .orElseThrow(PasskeyService::badCredential);
        RegistrationResult result;
        try {
            result = rp.finishRegistration(FinishRegistrationOptions.builder()
                    .request(PublicKeyCredentialCreationOptions.fromJson(stored.requestJson()))
                    .response(parsed).build());
        } catch (com.yubico.webauthn.exception.RegistrationFailedException | IOException e) {
            audit.record(userId, "PASSKEY_REGISTER_FAILED", "USER", userId.toString(), null);
            throw badCredential();
        }
        store.lockUser(userId);
        if (store.count(userId) >= MAX_PASSKEYS) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can register at most " + MAX_PASSKEYS + " passkeys");
        }
        String transports = result.getKeyId().getTransports()
                .map(set -> set.stream().map(t -> t.getId()).collect(Collectors.joining(",")))
                .orElse(null);
        try {
            store.insert(userId, result.getKeyId().getId(), result.getPublicKeyCose(), result.getSignatureCount(),
                    name, transports);
        } catch (DuplicateKeyException e) {
            throw ApiException.fieldError(HttpStatus.CONFLICT, "credential", "this passkey is already registered");
        }
        audit.record(userId, "PASSKEY_ADDED", "USER", userId.toString(), null);
        return store.find(userId, result.getKeyId().getId()).orElseThrow();
    }

    // ------------------------------------------------------------------ sign-in (public)

    /** A discoverable-credential request: it does not name an account, so it leaks nothing about anyone. */
    @Transactional
    public JsonNode loginOptions() {
        AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder()
                .userVerification(UserVerificationRequirement.REQUIRED).timeout(120_000).build());
        try {
            store.saveChallenge(request.getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url(), "LOGIN",
                    null, null, request.toJson(), clock.instant().plus(CHALLENGE_TTL));
            return json.readTree(request.toCredentialsGetJson()).get("publicKey");
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialise login options", e);
        }
    }

    /** Verifies the assertion and returns the user it belongs to. Any failure is the same 401. */
    @Transactional(noRollbackFor = ApiException.class)
    public UUID login(JsonNode credential) {
        try {
            AssertionResult result = finishAssertion(credential, "LOGIN", null, null);
            return store.userIdForHandle(result.getCredential().getUserHandle()).orElseThrow();
        } catch (RuntimeException e) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "Passkey sign-in failed");
        }
    }

    // ------------------------------------------------------------------ step-up

    @Transactional
    public JsonNode stepUpOptions(UUID userId, String action) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        AssertionRequest request = rp.startAssertion(StartAssertionOptions.builder().username(user.getEmail())
                .userVerification(UserVerificationRequirement.REQUIRED).timeout(120_000).build());
        try {
            store.saveChallenge(request.getPublicKeyCredentialRequestOptions().getChallenge().getBase64Url(),
                    "STEP_UP", userId, action, request.toJson(), clock.instant().plus(CHALLENGE_TTL));
            return json.readTree(request.toCredentialsGetJson()).get("publicKey");
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialise step-up options", e);
        }
    }

    /** Throws 400 (field {@code credential}) unless the assertion is valid for this user and this action. */
    @Transactional(noRollbackFor = ApiException.class)
    public void verifyStepUp(UUID userId, String action, JsonNode credential) {
        AssertionResult result;
        try {
            result = finishAssertion(credential, "STEP_UP", userId, action);
        } catch (RuntimeException e) {
            audit.record(userId, "STEP_UP_PASSKEY_FAILED", "USER", userId.toString(), "action=" + action);
            throw badCredential();
        }
        Optional<UUID> owner = store.userIdForHandle(result.getCredential().getUserHandle());
        if (owner.isEmpty() || !owner.get().equals(userId)) {
            throw badCredential();
        }
    }

    // ------------------------------------------------------------------ management

    @Transactional(readOnly = true)
    public List<PasskeyDto> list(UUID userId) {
        return store.list(userId);
    }

    @Transactional
    public void delete(UUID userId, UUID passkeyId) {
        if (!store.delete(userId, passkeyId)) {
            throw ApiException.notFound("Passkey");
        }
        audit.record(userId, "PASSKEY_REMOVED", "USER", userId.toString(), null);
    }

    // ------------------------------------------------------------------ internals

    /**
     * Takes the stored (single-use) request for the credential's challenge and lets the library verify the
     * assertion against it. {@code expectedUser} / {@code expectedAction} are checked for step-up requests.
     */
    private AssertionResult finishAssertion(JsonNode credential, String purpose, UUID expectedUser,
                                            String expectedAction) {
        try {
            PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> parsed =
                    PublicKeyCredential.parseAssertionResponseJson(credential.toString());
            String challenge = parsed.getResponse().getClientData().getChallenge().getBase64Url();
            PasskeyStore.StoredChallenge stored = store.takeChallenge(challenge, purpose, clock.instant())
                    .orElseThrow(() -> new IllegalArgumentException("unknown or expired challenge"));
            if (expectedUser != null && !expectedUser.equals(stored.userId())) {
                throw new IllegalArgumentException("challenge belongs to someone else");
            }
            if (expectedAction != null && !expectedAction.equals(stored.action())) {
                throw new IllegalArgumentException("challenge was issued for another action");
            }
            AssertionResult result = rp.finishAssertion(FinishAssertionOptions.builder()
                    .request(AssertionRequest.fromJson(stored.requestJson())).response(parsed).build());
            if (!result.isSuccess()) {
                throw new IllegalArgumentException("assertion not successful");
            }
            if (!result.isSignatureCounterValid()) {
                // A regressing counter means the authenticator may have been cloned.
                throw new IllegalArgumentException("signature counter regressed");
            }
            store.touch(result.getCredential().getCredentialId(), result.getSignatureCount());
            return result;
        } catch (IOException | com.yubico.webauthn.exception.AssertionFailedException e) {
            throw new IllegalArgumentException("assertion rejected", e);
        }
    }

    private static ApiException badCredential() {
        return ApiException.fieldError(HttpStatus.BAD_REQUEST, "credential", "could not be verified");
    }

    /** For tests: every origin the library accepts. */
    Set<String> allowedOrigins() {
        return rp.getOrigins();
    }
}
