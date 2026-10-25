package com.quantgen.risk;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * F-RISK-5: the text of a loan document's PDF pages, masked before it goes anywhere near a model (CLAUDE.md AI rules):
 * account, card, Aadhaar and phone numbers keep their last 4 digits, PANs and email addresses are replaced. Images
 * are never read (they can't be masked reliably). Also checks that a value the model cites is really on its page.
 */
final class DocumentText {

    static final int MAX_PAGES = 10;
    static final int MAX_PAGE_CHARS = 3000;

    /** 9+ digits in a row, or 4-4-4(-4) groups (cards, Aadhaar); amounts with commas and dates don't match. */
    private static final Pattern LONG_NUMBER = Pattern.compile(
            "(?<![0-9])(?:[0-9]{5,15}([0-9]{4})|[0-9]{4}[ -][0-9]{4}[ -][0-9]{0,4}[ -]?([0-9]{4}))(?![0-9])");
    private static final Pattern PAN = Pattern.compile("(?<![0-9A-Za-z])[A-Z]{5}[0-9]{4}[A-Z](?![0-9A-Za-z])");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern NUMBER = Pattern.compile("[0-9][0-9,]*(?:\\.[0-9]+)?");
    private static final Pattern NUMERIC_VALUE = Pattern.compile(
            "^(?:₹|rs\\.?|inr)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:/-)?$", Pattern.CASE_INSENSITIVE);

    record Page(int page, String text) {
    }

    /** A PDF's pages (first {@link #MAX_PAGES}), masked; empty pages are skipped. Throws when it can't be read. */
    static List<Page> pdfPages(byte[] pdf) throws IOException {
        List<Page> pages = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            PDFTextStripper stripper = new PDFTextStripper();
            int n = Math.min(doc.getNumberOfPages(), MAX_PAGES);
            for (int p = 1; p <= n; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = mask(stripper.getText(doc).replaceAll("[\\p{Cntrl}&&[^\n]]", " ")
                        .replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").strip());
                if (!text.isEmpty()) {
                    pages.add(new Page(p, text.length() > MAX_PAGE_CHARS ? text.substring(0, MAX_PAGE_CHARS) : text));
                }
            }
        }
        return pages;
    }

    static String mask(String text) {
        Matcher m = LONG_NUMBER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, "••••" + (m.group(1) != null ? m.group(1) : m.group(2)));
        }
        m.appendTail(out);
        return EMAIL.matcher(PAN.matcher(out).replaceAll("[PAN]")).replaceAll("[email]");
    }

    /** A value as an amount ("₹52,000.00", "Rs. 52000/-"), or null when it isn't one. */
    static BigDecimal amount(String value) {
        Matcher m = NUMERIC_VALUE.matcher(value.strip());
        return m.matches() ? new BigDecimal(m.group(1).replace(",", "")) : null;
    }

    /**
     * Whether {@code value} is printed on the page: an amount matches any number on it with the same value (so
     * "52,000" matches "52000.00"); other text matches case- and spacing-insensitively.
     */
    static boolean appearsOn(String value, String pageText) {
        BigDecimal wanted = amount(value);
        if (wanted != null) {
            Matcher m = NUMBER.matcher(pageText);
            while (m.find()) {
                if (new BigDecimal(m.group().replace(",", "")).compareTo(wanted) == 0) {
                    return true;
                }
            }
            return false;
        }
        String v = squash(value);
        return !v.isEmpty() && squash(pageText).contains(v);
    }

    private static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private DocumentText() {
    }
}
