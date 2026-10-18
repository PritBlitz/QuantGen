package com.quantgen.analytics;

/**
 * The one definition of income, spending, invested and borrowed (PRD 11.4.14), shared by the admin and customer
 * analytics so their numbers agree. It classifies every ledger line on a CUSTOMER account:
 *
 * <ul>
 *   <li>income: DEPOSIT credits, and TRANSFER credits from another user;</li>
 *   <li>spending: TRANSFER debits to another user, and LOAN_REPAYMENT debits;</li>
 *   <li>moves between a user's own accounts: {@code OTHER}, excluded from both;</li>
 *   <li>TRADE debits are INVESTED and TRADE credits DIVESTED; LOAN_DISBURSAL credits are BORROWED.</li>
 * </ul>
 *
 * A REVERSAL line is classified as the original entry's line it undoes, with a negative sign, so a reversed
 * transfer cancels out of spending instead of showing up as income.
 */
final class LedgerKinds {

    static final String INCOME = "'DEPOSIT','TRANSFER_IN'";
    static final String SPENDING = "'TRANSFER_OUT','LOAN_REPAYMENT'";

    private LedgerKinds() {
    }

    /**
     * A CTE named {@code cl} over ledger lines in [:lo, :hi), with columns line_id, user_id, created_at, day (IST),
     * journal_entry_id, description, counterparty, sgn (+1 or -1), amt (signed) and kind. {@code userFilter} adds
     * {@code AND a.user_id = :uid} for personal queries.
     */
    static String cl(boolean userFilter) {
        return """
                cl AS (
                  SELECT x.*,
                         CASE
                           WHEN x.base_type = 'DEPOSIT' AND x.odir = 'CREDIT' THEN 'DEPOSIT'
                           WHEN x.base_type = 'TRANSFER' AND x.odir = 'CREDIT' AND x.other_user THEN 'TRANSFER_IN'
                           WHEN x.base_type = 'TRANSFER' AND x.odir = 'DEBIT' AND x.other_user THEN 'TRANSFER_OUT'
                           WHEN x.base_type = 'LOAN_REPAYMENT' AND x.odir = 'DEBIT' THEN 'LOAN_REPAYMENT'
                           WHEN x.base_type = 'TRADE' AND x.odir = 'DEBIT' THEN 'INVESTED'
                           WHEN x.base_type = 'TRADE' AND x.odir = 'CREDIT' THEN 'DIVESTED'
                           WHEN x.base_type = 'LOAN_DISBURSAL' AND x.odir = 'CREDIT' THEN 'BORROWED'
                           ELSE 'OTHER'
                         END AS kind
                    FROM (
                      SELECT l.id AS line_id, a.user_id, l.created_at,
                             (l.created_at AT TIME ZONE 'Asia/Kolkata')::date AS day,
                             l.journal_entry_id, e.description, l.counterparty,
                             CASE WHEN e.type = 'REVERSAL' THEN -1 ELSE 1 END AS sgn,
                             l.amount * CASE WHEN e.type = 'REVERSAL' THEN -1 ELSE 1 END AS amt,
                             COALESCE(oe.type, e.type) AS base_type,
                             CASE WHEN e.type = 'REVERSAL'
                                  THEN CASE l.direction WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
                                  ELSE l.direction END AS odir,
                             EXISTS (SELECT 1 FROM ledger_lines ol JOIN accounts oa ON oa.id = ol.ledger_account_id
                                      WHERE ol.journal_entry_id = l.journal_entry_id
                                        AND oa.user_id <> a.user_id) AS other_user
                        FROM ledger_lines l
                        JOIN accounts a ON a.id = l.ledger_account_id
                        JOIN journal_entries e ON e.id = l.journal_entry_id
                        LEFT JOIN journal_entries oe ON oe.id = e.reverses_entry_id
                       WHERE l.created_at >= :lo AND l.created_at < :hi
                """ + (userFilter ? "         AND a.user_id = :uid\n" : "") + """
                    ) x
                )
                """;
    }
}
