package com.quantgen.agent.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-AGT-4 masking before anything reaches the model: any run of 10–19 digits (account, card, phone and consumer
 * numbers) becomes {@code ••••1234}. Tool results carrying user-written text are wrapped as data, never instructions.
 */
public final class Redactor {

    private static final Pattern LONG_NUMBER = Pattern.compile(
            "(?<![0-9A-Za-z-])([0-9]{6,15})([0-9]{4})(?![0-9A-Za-z-])"); // whole numbers only, never inside ids
    static final int MAX_RESULT = 6000;

    private Redactor() {
    }

    public static String mask(String text) {
        if (text == null) {
            return "";
        }
        Matcher m = LONG_NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "••••" + m.group(2));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** A tool result for the model: masked, capped, and marked as data from the bank (not instructions). */
    public static String asData(String json) {
        String masked = mask(json);
        if (masked.length() > MAX_RESULT) {
            masked = masked.substring(0, MAX_RESULT) + "…(truncated)";
        }
        return "<bank_data>\n" + masked + "\n</bank_data>";
    }
}
