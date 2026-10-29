package com.quantgen.account.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Money;
import com.quantgen.ledger.service.LedgerService;

/**
 * Closing a savings, checking or goal account (fixed deposits close through their own endpoint). Only an empty
 * account with nothing depending on it closes; otherwise the answer lists what's in the way. Closing needs step-up.
 * The account's ledger row is locked first, the same lock every posting takes, so no payment can land between the
 * balance check and the close; afterwards the posting guard refuses money in or out. Its card is frozen.
 */
@Service
public class AccountClosureService {

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final StepUpService stepUp;
    private final AuditService audit;

    AccountClosureService(JdbcTemplate jdbc, AccountService accounts, LedgerService ledger, StepUpService stepUp,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
        this.stepUp = stepUp;
        this.audit = audit;
    }

    @Transactional
    public void close(UUID userId, UUID accountId, String stepUpToken) {
        AccountRef account = accounts.requireOwned(userId, accountId);
        if (account.type() == AccountType.FIXED_DEPOSIT) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "id", "close a fixed deposit from Fixed deposits");
        }
        stepUp.require(userId, StepUpAction.SECURITY_SETTINGS, stepUpToken);
        // Same row lock as LedgerService.post: an in-flight payment finishes first or waits and then sees CLOSED.
        jdbc.queryForList("SELECT id FROM ledger_accounts WHERE id = ? FOR UPDATE", UUID.class, accountId);
        String status = jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, accountId);
        if (AccountStatus.CLOSED.name().equals(status)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This account is already closed");
        }
        if (AccountStatus.FROZEN.name().equals(status)) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "A frozen account can't be closed; contact support");
        }
        List<String> blockers = blockers(accountId);
        if (!blockers.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This account can't be closed yet: "
                    + String.join("; ", blockers) + ".");
        }
        jdbc.update("UPDATE accounts SET status = 'CLOSED', updated_at = now() WHERE id = ?", accountId);
        jdbc.update("UPDATE cards SET frozen = TRUE, updated_at = now() WHERE account_id = ?", accountId);
        audit.record(userId, "ACCOUNT_CLOSED", "ACCOUNT", accountId.toString(),
                account.type() + " " + AccountNumbers.mask(account.accountNumber()));
    }

    /** Everything that still needs this account, in words the customer can act on. */
    private List<String> blockers(UUID id) {
        List<String> out = new ArrayList<>();
        BigDecimal balance = ledger.balancesOf(List.of(id)).getOrDefault(id, BigDecimal.ZERO);
        if (balance.signum() != 0) {
            out.add("move the balance of ₹" + Money.toJson(balance) + " out first");
        }
        BigDecimal held = ledger.holdsTotals(List.of(id)).getOrDefault(id, BigDecimal.ZERO);
        if (held.signum() != 0) {
            out.add("₹" + Money.toJson(held) + " is on hold (a card payment or a transfer under review)");
        }
        if (count("SELECT count(*) FROM topups WHERE account_id = ? AND status = 'CREATED'", id) > 0) {
            out.add("a Razorpay top-up to it isn't finished (wait up to 30 minutes)");
        }
        if (count("SELECT count(*) FROM loans WHERE disburse_account_id = ? AND status IN ('PENDING', 'APPROVED', 'ACTIVE')", id) > 0) {
            out.add("a loan is paid into or repaid from it");
        }
        if (count("SELECT count(*) FROM fixed_deposits WHERE payout_account_id = ? AND status = 'ACTIVE'", id) > 0) {
            out.add("a fixed deposit pays out to it");
        }
        if (count("SELECT count(*) FROM scheduled_transfers WHERE (from_account_id = ? OR to_account_id = ?) "
                + "AND status IN ('ACTIVE', 'PAUSED')", id, id) > 0) {
            out.add("cancel the scheduled transfers that use it");
        }
        if (count("""
                SELECT count(*) FROM autopilot_rules r JOIN autopilot_rule_versions v ON v.rule_id = r.id AND v.version = r.version
                 WHERE r.status IN ('ACTIVE', 'PAUSED') AND v.dsl LIKE ?
                """, "%" + id + "%") > 0) {
            out.add("delete or edit the Autopilot rules that use it");
        }
        if (count("SELECT count(*) FROM handles WHERE account_id = ?", id) > 0) {
            out.add("point your handle at another account");
        }
        if (count("SELECT count(*) FROM round_up_settings WHERE enabled AND (account_id = ? OR goal_account_id = ?)", id, id) > 0) {
            out.add("turn off the round-ups that use it");
        }
        return out;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
