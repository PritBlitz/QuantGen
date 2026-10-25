package com.quantgen.agent.run;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F-AGT-4 masking before anything reaches the model: any run of 10–19 digits (account, card, phone and consumer
 * numbers) becomes {@code ••••1234}, and an Indian PAN ({@code ABCDE1234F}) becomes {@code [PAN]}. Tool results
 * carrying user-written text are wrapped as data, never instructions.
 */
public final class Redactor {

    private static final Pattern LONG_NUMBER = Pattern.compile(
            "(?<![0-9A-Za-z-])([0-9]{6,15})([0-9]{4})(?![0-9A-Za-z-])"); // whole numbers only, never inside ids
    private static final Pattern PAN = Pattern.compile("(?<![0-9A-Za-z])[A-Z]{5}[0-9]{4}[A-Z](?![0-9A-Za-z])");
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
        return PAN.matcher(out).replaceAll("[PAN]");
    }

    /** A tool result for the model: masked, capped, and marked as data from the bank (not instructions). */
    public static String asData(String json) {
        return asData(json, MAX_RESULT);
    }

    /** {@link #asData(String)} with a different size cap (back-office memos carry document text). */
    public static String asData(String json, int maxChars) {
        String masked = mask(json);
        if (masked.length() > maxChars) {
            masked = masked.substring(0, maxChars) + "…(truncated)";
        }
        return "<bank_data>\n" + masked + "\n</bank_data>";
    }
}
