package com.quantgen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

/** Posting rules of LedgerService, exercised directly. */
class LedgerServiceIT extends IntegrationTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    AccountService accounts;

    @Autowired
    TransactionTemplate tx;

    private UUID newFundedAccount(String amount) {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID id = Bank.firstAccountId(this, s);
        if (new BigDecimal(amount).signum() > 0) {
            tx.executeWithoutResult(st -> ledger.post(JournalRequest.builder(JournalType.DEPOSIT, "seed", null)
                    .debit(SystemAccount.BANK_CASH.id(), new BigDecimal(amount), null)
                    .credit(id, new BigDecimal(amount), null).build()));
        }
        return id;
    }

    @Test
    void postRequiresATransaction() {
        UUID a = newFundedAccount("0");
        assertThatThrownBy(() -> ledger.post(JournalRequest.builder(JournalType.DEPOSIT, "x", null)
                .debit(SystemAccount.BANK_CASH.id(), BigDecimal.TEN, null).credit(a, BigDecimal.TEN, null).build()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void rejectsUnbalancedOrMalformedEntries() {
        UUID a = newFundedAccount("100");
        UUID b = newFundedAccount("0");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, new BigDecimal("10"), null).credit(b, new BigDecimal("9.99"), null).build(), "Unbalanced");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, new BigDecimal("-1"), null).credit(b, new BigDecimal("-1"), null).build(), "positive");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, BigDecimal.ZERO, null).credit(b, BigDecimal.ZERO, null).build(), "positive");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, new BigDecimal("1.00001"), null).credit(b, new BigDecimal("1.00001"), null).build(), "4 decimal");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, BigDecimal.ONE, null).build(), "two lines");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, BigDecimal.ONE, null).credit(a, BigDecimal.ONE, null).build(), "two accounts");
        assertPostFails(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, BigDecimal.ONE, null).credit(UUID.randomUUID(), BigDecimal.ONE, null).build(), "unknown");
        assertThat(ledger.balanceOf(a)).isEqualByComparingTo("100");
    }

    @Test
    void customerBalanceCannotGoNegative() {
        UUID a = newFundedAccount("50");
        UUID b = newFundedAccount("0");
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.post(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, new BigDecimal("50.01"), null).credit(b, new BigDecimal("50.01"), null).build())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));

        // Exactly the balance is fine, and system accounts may go negative.
        PostedEntry e = tx.execute(st -> ledger.post(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, new BigDecimal("50"), null).credit(b, new BigDecimal("50"), null).build()));
        assertThat(e.lineFor(a).balanceAfter()).isEqualByComparingTo("0");
        assertThat(e.lineFor(b).balanceAfter()).isEqualByComparingTo("50");
    }

    @Test
    void frozenAccountCanReceiveButNotSend() {
        UUID a = newFundedAccount("100");
        UUID b = newFundedAccount("100");
        accounts.changeStatus(a, AccountStatus.FROZEN);

        assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.post(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, BigDecimal.ONE, null).credit(b, BigDecimal.ONE, null).build())))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_FROZEN));

        tx.executeWithoutResult(st -> ledger.post(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(b, BigDecimal.ONE, null).credit(a, BigDecimal.ONE, null).build()));
        assertThat(ledger.balanceOf(a)).isEqualByComparingTo("101");

        accounts.changeStatus(a, AccountStatus.ACTIVE);
        tx.executeWithoutResult(st -> ledger.post(JournalRequest.builder(JournalType.TRANSFER, "x", null)
                .debit(a, BigDecimal.ONE, null).credit(b, BigDecimal.ONE, null).build()));
        assertThat(ledger.balanceOf(a)).isEqualByComparingTo("100");
    }

    @Test
    void reversalMirrorsTheEntryOnlyOnce() {
        UUID a = newFundedAccount("100");
        UUID b = newFundedAccount("0");
        PostedEntry original = tx.execute(st -> ledger.post(JournalRequest.builder(JournalType.TRANSFER, "oops", null)
                .debit(a, new BigDecimal("30"), null).credit(b, new BigDecimal("30"), null).build()));

        PostedEntry reversal = tx.execute(st -> ledger.reverse(original.journalEntryId(), "wrong recipient", null));
        assertThat(reversal.lines()).allMatch(l -> l.type() == JournalType.REVERSAL);
        assertThat(ledger.balanceOf(a)).isEqualByComparingTo("100");
        assertThat(ledger.balanceOf(b)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT reverses_entry_id FROM journal_entries WHERE id = ?", UUID.class,
                reversal.journalEntryId())).isEqualTo(original.journalEntryId());

        assertThatThrownBy(() -> tx.execute(st -> ledger.reverse(original.journalEntryId(), "again", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void reconcileDetectsATamperedCachedBalance() {
        UUID a = newFundedAccount("10");
        assertThat(ledger.reconcile().balanced()).isTrue();

        jdbc.update("UPDATE ledger_accounts SET balance = balance + 1 WHERE id = ?", a);
        try {
            var report = ledger.reconcile();
            assertThat(report.balanced()).isFalse();
            assertThat(report.ledgerSum()).isEqualByComparingTo("0");
            assertThat(report.mismatches()).anySatisfy(m -> {
                assertThat(m.ledgerAccountId()).isEqualTo(a);
                assertThat(m.cachedBalance()).isEqualByComparingTo("11");
                assertThat(m.computedBalance()).isEqualByComparingTo("10");
            });
        } finally {
            jdbc.update("UPDATE ledger_accounts SET balance = balance - 1 WHERE id = ?", a);
        }
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    private void assertPostFails(JournalRequest request, String messagePart) {
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.post(request)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }
}
