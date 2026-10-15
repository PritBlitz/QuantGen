package com.quantgen.account.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.money.Money;
import com.quantgen.ledger.domain.Direction;
import com.quantgen.ledger.service.LedgerLineView;

/** Renders an account statement as RFC 4180 CSV, safe to open in spreadsheet apps. */
final class StatementCsv {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(TimeConfig.BUSINESS_ZONE);

    private StatementCsv() {
    }

    static String render(String accountNumber, LocalDate from, LocalDate to, List<LedgerLineView> lines) {
        StringBuilder out = new StringBuilder(128 + lines.size() * 96);
        row(out, "Account", accountNumber);
        row(out, "Period", from + " to " + to + " (IST)");
        out.append("\r\n");
        row(out, "Date (IST)", "Type", "Description", "Counterparty", "Debit", "Credit", "Balance", "Transaction ID");
        for (LedgerLineView l : lines) {
            boolean debit = l.direction() == Direction.DEBIT;
            row(out,
                    TIMESTAMP.format(l.createdAt()),
                    l.type().name(),
                    l.description(),
                    l.counterparty(),
                    debit ? Money.toJson(l.amount()) : "",
                    debit ? "" : Money.toJson(l.amount()),
                    Money.toJson(l.balanceAfter()),
                    l.lineId().toString());
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    /**
     * Quotes every cell and neutralizes spreadsheet formulas: text starting with = + - @ (or tab/CR) is
     * prefixed with an apostrophe so user-entered notes can never execute as formulas.
     */
    static String cell(String value) {
        if (value == null) {
            return "\"\"";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !isNumber(v)) {
            v = "'" + v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static boolean isNumber(String v) {
        return v.matches("-?[0-9]+(\\.[0-9]+)?");
    }
}
