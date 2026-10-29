package com.quantgen.trading.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches NSE's official end-of-day file ("bhavcopy", UDiFF format) for one day and returns the EQ rows for the
 * requested NSE symbols (D-047). The host comes from config only, redirects are refused, and the zip is read in
 * memory with size caps (never written to disk), so neither SSRF nor zip-slip applies. Every number is parsed as
 * BigDecimal and checked; anything unexpected fails the whole day rather than storing a doubtful price.
 */
@Component
public class BhavcopyClient {

    static final int MAX_ZIP_BYTES = 5 * 1024 * 1024;
    static final int MAX_CSV_BYTES = 20 * 1024 * 1024;
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36";
    private static final List<String> COLUMNS = List.of("TradDt", "TckrSymb", "SctySrs", "OpnPric", "HghPric", "LwPric",
            "ClsPric", "PrvsClsgPric", "TtlTradgVol");

    public record Row(String nseSymbol, LocalDate day, BigDecimal open, BigDecimal high, BigDecimal low,
                      BigDecimal close, BigDecimal prevClose, long volume) {
    }

    public static class BhavcopyException extends RuntimeException {
        BhavcopyException(String message) {
            super(message, null, false, false);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String baseUrl;

    @Autowired
    public BhavcopyClient(@Value("${qg.market.sync.base-url:https://nsearchives.nseindia.com/content/cm/}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    static String fileName(LocalDate day) {
        return "BhavCopy_NSE_CM_0_0_0_" + YYYYMMDD.format(day) + "_F_0000.csv";
    }

    /** The day's rows for {@code nseSymbols}; empty if NSE has no file for that day (a holiday, or not out yet). */
    public Optional<List<Row>> fetch(LocalDate day, Set<String> nseSymbols) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + fileName(day) + ".zip"))
                .header("User-Agent", USER_AGENT).header("Accept", "application/zip,*/*")
                .timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<InputStream> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new BhavcopyException("NSE unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BhavcopyException("interrupted");
        }
        try (InputStream body = res.body()) {
            if (res.statusCode() == 404) {
                return Optional.empty();
            }
            if (res.statusCode() != 200) {
                throw new BhavcopyException("NSE answered " + res.statusCode());
            }
            return Optional.of(parseZip(readCapped(body, MAX_ZIP_BYTES), day, nseSymbols));
        } catch (IOException e) {
            throw new BhavcopyException("reading NSE's answer failed: " + e.getClass().getSimpleName());
        }
    }

    static List<Row> parseZip(byte[] zip, LocalDate day, Set<String> nseSymbols) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (!e.isDirectory() && e.getName().equals(fileName(day))) {
                    return parseCsv(new String(readCapped(in, MAX_CSV_BYTES), StandardCharsets.ISO_8859_1), day,
                            nseSymbols);
                }
            }
        }
        throw new BhavcopyException("the zip has no " + fileName(day));
    }

    static List<Row> parseCsv(String csv, LocalDate day, Set<String> nseSymbols) {
        String[] lines = csv.split("\r?\n");
        List<String> header = List.of(lines[0].trim().split(",", -1));
        Map<String, Integer> col = new HashMap<>();
        for (String c : COLUMNS) {
            int i = header.indexOf(c);
            if (i < 0) {
                throw new BhavcopyException("column " + c + " is missing");
            }
            col.put(c, i);
        }
        List<Row> rows = new ArrayList<>();
        for (int n = 1; n < lines.length; n++) {
            String[] f = lines[n].split(",", -1);
            if (f.length != header.size() || !"EQ".equals(f[col.get("SctySrs")])
                    || !nseSymbols.contains(f[col.get("TckrSymb")])) {
                continue;
            }
            rows.add(row(f, col, day));
        }
        return rows;
    }

    private static Row row(String[] f, Map<String, Integer> col, LocalDate day) {
        String symbol = f[col.get("TckrSymb")];
        try {
            LocalDate traded = LocalDate.parse(f[col.get("TradDt")]);
            BigDecimal open = price(f[col.get("OpnPric")]);
            BigDecimal high = price(f[col.get("HghPric")]);
            BigDecimal low = price(f[col.get("LwPric")]);
            BigDecimal close = price(f[col.get("ClsPric")]);
            BigDecimal prev = price(f[col.get("PrvsClsgPric")]);
            long volume = Long.parseLong(f[col.get("TtlTradgVol")]);
            if (!traded.equals(day) || volume < 0 || low.compareTo(open.min(close)) > 0
                    || open.max(close).compareTo(high) > 0) {
                throw new BhavcopyException("inconsistent row for " + symbol);
            }
            return new Row(symbol, day, open, high, low, close, prev, volume);
        } catch (RuntimeException e) {
            throw e instanceof BhavcopyException b ? b : new BhavcopyException("unreadable row for " + symbol);
        }
    }

    private static BigDecimal price(String s) {
        BigDecimal p = new BigDecimal(s.trim());
        if (p.signum() <= 0 || p.scale() > 4 || p.compareTo(new BigDecimal("10000000")) >= 0) {
            throw new BhavcopyException("price out of range");
        }
        return p;
    }

    private static byte[] readCapped(InputStream in, int max) throws IOException {
        byte[] bytes = in.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new BhavcopyException("NSE's file is larger than " + max + " bytes");
        }
        return bytes;
    }
}
