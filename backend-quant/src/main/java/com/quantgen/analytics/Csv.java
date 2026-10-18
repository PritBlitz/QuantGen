package com.quantgen.analytics;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 writer. Cells that start with a spreadsheet formula character are prefixed with an apostrophe
 * so opening an export in Excel can never run a formula (CSV injection).
 */
final class Csv {

    private Csv() {
    }

    static List<String> row(Object... cells) {
        List<String> out = new ArrayList<>(cells.length);
        for (Object c : cells) {
            out.add(c == null ? "" : c.toString());
        }
        return out;
    }

    static String render(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(escape(row.get(i)));
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }

    static String escape(String cell) {
        String v = cell == null ? "" : cell;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0 && !isNumber(v)) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    /** Negative numbers start with '-' but are data, not formulas. */
    private static boolean isNumber(String v) {
        return v.matches("-?\\d+(\\.\\d+)?");
    }
}
