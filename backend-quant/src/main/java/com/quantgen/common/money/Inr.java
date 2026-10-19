package com.quantgen.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Display formatting of rupees for server-rendered text (intent cards, rule sentences). Never for arithmetic. */
public final class Inr {

    private Inr() {
    }

    /** ₹1,23,456.78: two decimals, Indian digit grouping (lakh, crore). */
    public static String format(BigDecimal amount) {
        String plain = amount.setScale(2, RoundingMode.HALF_EVEN).abs().toPlainString();
        String whole = plain.substring(0, plain.indexOf('.'));
        String frac = plain.substring(plain.indexOf('.'));
        StringBuilder out = new StringBuilder();
        if (whole.length() > 3) {
            String head = whole.substring(0, whole.length() - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    out.append(',');
                }
                out.append(head.charAt(i));
            }
            out.append(',').append(whole.substring(whole.length() - 3));
        } else {
            out.append(whole);
        }
        return (amount.signum() < 0 ? "−₹" : "₹") + out + frac;
    }
}
