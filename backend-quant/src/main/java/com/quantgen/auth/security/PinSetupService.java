package com.quantgen.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.domain.User;
import com.quantgen.auth.domain.UserRepository;
import com.quantgen.auth.service.AuthProperties;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;

/**
 * Setting or changing the transaction PIN (PUT /users/me/pin).
 *
 * <p>Who may do this: the signed-in owner, and only with proof beyond the access token. If the user already has
 * a step-up method, that is a step-up token for SECURITY_SETTINGS. If they have none yet (the first PIN), there
 * is nothing to step up with, so the account password is required instead. Wrong passwords here count toward
 * the same lockout as login, so this endpoint is not a password-guessing oracle for a stolen access token.
 */
@Service
public class PinSetupService {

    private final PinService pins;
    private final StepUpService stepUp;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final AuthProperties props;
    private final AuditService audit;
    private final Clock clock;

    public PinSetupService(PinService pins, StepUpService stepUp, UserRepository users, PasswordEncoder encoder,
                           AuthProperties props, AuditService audit, Clock clock) {
        this.pins = pins;
        this.stepUp = stepUp;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void setPin(UUID userId, String stepUpToken, String pin, String password) {
        PinService.validateFormat(pin);
        if (!stepUp.methods(userId).isEmpty()) {
            stepUp.require(userId, StepUpAction.SECURITY_SETTINGS, stepUpToken);
        } else {
            checkPassword(userId, password);
        }
        pins.setPin(userId, pin);
    }

    private void checkPassword(UUID userId, String password) {
        if (password == null || password.isBlank()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "password", "is required to set your first PIN");
        }
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        Instant now = clock.instant();
        if (user.isLocked(now)) {
            throw ApiException.rateLimited("Too many failed attempts. Try again later.",
                    Duration.between(now, user.getLockedUntil()).toSeconds());
        }
        if (!encoder.matches(password, user.getPasswordHash())) {
            AuthProperties.Auth cfg = props.auth();
            user.recordFailedLogin(now, cfg.maxFailedLogins(), cfg.failedLoginWindow(), cfg.lockoutDuration());
            audit.record(userId, "PIN_SETUP_BAD_PASSWORD", "USER", userId.toString(), null);
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "password", "is incorrect");
        }
    }
}
