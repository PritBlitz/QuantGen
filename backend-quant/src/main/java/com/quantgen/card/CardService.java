package com.quantgen.card;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.crypto.FieldCrypto;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.flags.FeatureFlagService;
import com.quantgen.common.money.Money;

/**
 * F-CARD-1/2: one virtual debit card per everyday account. The PAN is encrypted at rest and found by its blind
 * index (D-032); it leaves the server only from {@link #reveal}, after step-up. The CVV is never stored: it is an
 * HMAC of the PAN and the current 30-second window, so it changes every 30 s.
 */
@Service
public class CardService {

    public static final String FLAG = "cards";
    static final String PAN_CTX = "card-pan";
    static final String CVV_CTX = "qg-card-cvv-v1";
    /** Non-issuer test range: no real card starts with 9100. */
    static final String PAN_PREFIX = "9100";
    static final long CVV_WINDOW_SECONDS = 30;
    static final int HIDE_AFTER_SECONDS = 30;
    static final BigDecimal DEFAULT_LIMIT = new BigDecimal("25000.0000");
    private static final DateTimeFormatter EXPIRY = DateTimeFormatter.ofPattern("MM/yy");

    public record CardControls(boolean frozen, boolean online, BigDecimal perTxnLimit,
                               List<MerchantCategory> blockedCategories) {
    }

    public record CardDto(UUID id, UUID accountId, String last4, String expiry, String status, CardControls controls) {
    }

    public record RevealDto(String pan, String cvv, String expiry, int hideAfterSeconds) {
    }

    /** A card as the sandbox sees it: the PAN in clear, server-side only. */
    record CardRow(UUID id, UUID userId, UUID accountId, String pan, String last4, String expiry,
                   CardControls controls) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final FieldCrypto crypto;
    private final StepUpService stepUp;
    private final FeatureFlagService flags;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    CardService(JdbcTemplate jdbc, AccountService accounts, FieldCrypto crypto, StepUpService stepUp,
                FeatureFlagService flags, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.crypto = crypto;
        this.stepUp = stepUp;
        this.flags = flags;
        this.audit = audit;
        this.clock = clock;
    }

    void requireEnabled() {
        if (!flags.isEnabled(FLAG)) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Cards are switched off");
        }
    }

    @Transactional
    public CardDto create(UUID userId, UUID accountId) {
        requireEnabled();
        AccountRef account = accounts.requireOwned(userId, accountId);
        if (!account.isEveryday()) {
            throw ApiException.notFound("Account"); // goals and FDs can't have cards
        }
        if (account.status() == AccountStatus.CLOSED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This account is closed");
        }
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cards WHERE account_id = ?)",
                Boolean.class, accountId);
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This account already has a card");
        }
        String pan = newPan();
        String expiry = YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).plusYears(4).format(EXPIRY);
        // A concurrent create for the same account fails on cards_account_uq and answers 409 (unique violation).
        UUID id = jdbc.queryForObject("""
                INSERT INTO cards (user_id, account_id, pan_enc, pan_index, last4, expiry, per_txn_limit, blocked_categories)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, userId, accountId, crypto.encrypt(pan, PAN_CTX), panIndex(pan), pan.substring(12),
                expiry, DEFAULT_LIMIT, MerchantCategory.GAMBLING.name());
        audit.record(userId, "CARD_CREATED", "CARD", id.toString(), "•••• " + pan.substring(12));
        return find(userId, id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public List<CardDto> list(UUID userId) {
        return jdbc.query(SELECT + " WHERE user_id = ? ORDER BY created_at, id", (rs, i) -> dto(rs), userId);
    }

    /** Full PAN and the current CVV, only with a live CARD_REVEAL step-up token. Audited, never logged. */
    @Transactional(noRollbackFor = ApiException.class)
    public RevealDto reveal(UUID userId, UUID cardId, String stepUpToken) {
        CardRow card = row(cardId).filter(c -> c.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Card"));
        stepUp.require(userId, StepUpAction.CARD_REVEAL, stepUpToken);
        audit.record(userId, "CARD_REVEALED", "CARD", cardId.toString(), "•••• " + card.last4());
        return new RevealDto(grouped(card.pan()), cvv(card.pan(), currentWindow()), card.expiry(), HIDE_AFTER_SECONDS);
    }

    @Transactional
    public CardDto updateControls(UUID userId, UUID cardId, Boolean frozen, Boolean online, BigDecimal perTxnLimit,
                                  List<MerchantCategory> blockedCategories) {
        CardDto before = jdbc.query(SELECT + " WHERE id = ? AND user_id = ? FOR UPDATE", (rs, i) -> dto(rs), cardId, userId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Card"));
        CardControls c = before.controls();
        boolean newFrozen = frozen == null ? c.frozen() : frozen;
        jdbc.update("""
                UPDATE cards SET frozen = ?, online = ?, per_txn_limit = ?, blocked_categories = ?, updated_at = now()
                 WHERE id = ?
                """, newFrozen, online == null ? c.online() : online,
                perTxnLimit == null ? c.perTxnLimit() : Money.normalize(perTxnLimit),
                encode(blockedCategories == null ? c.blockedCategories() : blockedCategories), cardId);
        String action = newFrozen != c.frozen() ? (newFrozen ? "CARD_FROZEN" : "CARD_UNFROZEN") : "CARD_CONTROLS_CHANGED";
        audit.record(userId, action, "CARD", cardId.toString(), "•••• " + before.last4());
        return find(userId, cardId).orElseThrow();
    }

    // ------------------------------------------------------------------ for the sandbox

    /** The card with this PAN, if any (by blind index; the PAN is never compared in SQL). */
    Optional<CardRow> findByPan(String pan) {
        List<UUID> ids = jdbc.queryForList("SELECT id FROM cards WHERE pan_index = ?", UUID.class, panIndex(pan));
        return ids.isEmpty() ? Optional.empty() : row(ids.getFirst());
    }

    long currentWindow() {
        return clock.instant().getEpochSecond() / CVV_WINDOW_SECONDS;
    }

    /** 3 digits from HMAC(index key, PAN | window). */
    String cvv(String pan, long window) {
        String mac = crypto.blindIndex(pan + "|" + window, CVV_CTX);
        return String.format("%03d", Long.parseLong(mac.substring(0, 8), 16) % 1000);
    }

    /** Valid through the last day of the expiry month (IST). */
    boolean isExpired(String expiry) {
        YearMonth ym = YearMonth.parse(expiry, EXPIRY);
        return YearMonth.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).isAfter(ym);
    }

    // ------------------------------------------------------------------ helpers

    private static final String SELECT = """
            SELECT id, user_id, account_id, last4, expiry, frozen, online, per_txn_limit, blocked_categories, pan_enc
              FROM cards
            """;

    private Optional<CardDto> find(UUID userId, UUID cardId) {
        return jdbc.query(SELECT + " WHERE id = ? AND user_id = ?", (rs, i) -> dto(rs), cardId, userId).stream().findFirst();
    }

    private Optional<CardRow> row(UUID cardId) {
        return jdbc.query(SELECT + " WHERE id = ?", (rs, i) -> new CardRow(rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getObject("account_id", UUID.class),
                crypto.decrypt(rs.getString("pan_enc"), PAN_CTX), rs.getString("last4"), rs.getString("expiry"),
                controls(rs)), cardId).stream().findFirst();
    }

    private static CardDto dto(ResultSet rs) throws SQLException {
        CardControls controls = controls(rs);
        return new CardDto(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("last4"),
                rs.getString("expiry"), controls.frozen() ? "FROZEN" : "ACTIVE", controls);
    }

    private static CardControls controls(ResultSet rs) throws SQLException {
        return new CardControls(rs.getBoolean("frozen"), rs.getBoolean("online"), rs.getBigDecimal("per_txn_limit"),
                decode(rs.getString("blocked_categories")));
    }

    private static String encode(List<MerchantCategory> categories) {
        Set<MerchantCategory> set = categories.isEmpty() ? EnumSet.noneOf(MerchantCategory.class) : EnumSet.copyOf(categories);
        return set.stream().map(Enum::name).collect(Collectors.joining(","));
    }

    private static List<MerchantCategory> decode(String stored) {
        return stored == null || stored.isBlank() ? List.of()
                : Arrays.stream(stored.split(",")).map(MerchantCategory::valueOf).toList();
    }

    private String panIndex(String pan) {
        return crypto.blindIndex(pan, PAN_CTX);
    }

    /** 16 digits: the 9100 prefix, 11 random digits and a Luhn check digit; unique among issued cards. */
    private String newPan() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder body = new StringBuilder(PAN_PREFIX);
            for (int i = 0; i < 11; i++) {
                body.append(random.nextInt(10));
            }
            String pan = body.toString() + luhnCheckDigit(body.toString());
            Boolean taken = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM cards WHERE pan_index = ?)",
                    Boolean.class, panIndex(pan));
            if (!Boolean.TRUE.equals(taken)) {
                return pan;
            }
        }
        throw new IllegalStateException("Could not generate a unique card number");
    }

    static int luhnCheckDigit(String body) {
        int sum = 0;
        for (int i = 0; i < body.length(); i++) {
            int d = body.charAt(body.length() - 1 - i) - '0';
            if (i % 2 == 0) { // doubled: these positions sit at odd offsets once the check digit is appended
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return (10 - sum % 10) % 10;
    }

    static boolean luhnValid(String digits) {
        return digits.length() >= 2 && luhnCheckDigit(digits.substring(0, digits.length() - 1))
                == digits.charAt(digits.length() - 1) - '0';
    }

    private static String grouped(String pan) {
        return pan.replaceAll("(\\d{4})(?=\\d)", "$1 ");
    }
}
