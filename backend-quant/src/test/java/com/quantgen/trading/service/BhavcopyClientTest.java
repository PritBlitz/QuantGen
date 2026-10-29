package com.quantgen.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import com.quantgen.trading.service.BhavcopyClient.BhavcopyException;
import com.quantgen.trading.service.BhavcopyClient.Row;

/** NSE's daily file is untrusted input: only well-formed EQ rows for our symbols, for the right day, get through. */
class BhavcopyClientTest {

    static final String HEADER = "TradDt,BizDt,Sgmt,Src,FinInstrmTp,FinInstrmId,ISIN,TckrSymb,SctySrs,XpryDt,"
            + "FininstrmActlXpryDt,StrkPric,OptnTp,FinInstrmNm,OpnPric,HghPric,LwPric,ClsPric,LastPric,PrvsClsgPric,"
            + "UndrlygPric,SttlmPric,OpnIntrst,ChngInOpnIntrst,TtlTradgVol,TtlTrfVal,TtlNbOfTxsExctd,SsnId,NewBrdLotQty,"
            + "Rmks,Rsvd1,Rsvd2,Rsvd3,Rsvd4";
    static final LocalDate DAY = LocalDate.of(2026, 10, 28);

    static String line(LocalDate day, String symbol, String series, String open, String high, String low, String close,
                       String prev) {
        return day + "," + day + ",CM,NSE,STK,1594,INE009A01021," + symbol + "," + series + ",,,,,NAME," + open + ","
                + high + "," + low + "," + close + "," + close + "," + prev + ",," + close + ",,,15636449,1.0,1,F1,1,,,,,";
    }

    static byte[] zip(String entryName, String csv) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry(entryName));
            z.write(csv.getBytes(StandardCharsets.ISO_8859_1));
            z.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void keepsOnlyOurEquityRows() throws IOException {
        String csv = String.join("\n", HEADER,
                line(DAY, "INFY", "EQ", "1130.00", "1133.80", "1103.40", "1123.15", "1163.00"),
                line(DAY, "INFY", "BE", "1.00", "1.00", "1.00", "1.00", "1.00"),          // another series
                line(DAY, "ZOMATO", "EQ", "200.00", "210.00", "190.00", "205.00", "201.00")); // not ours
        List<Row> rows = BhavcopyClient.parseZip(zip(BhavcopyClient.fileName(DAY), csv), DAY, Set.of("INFY"));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).close()).isEqualByComparingTo(new BigDecimal("1123.15"));
        assertThat(rows.get(0).prevClose()).isEqualByComparingTo(new BigDecimal("1163.00"));
        assertThat(rows.get(0).volume()).isEqualTo(15636449L);
    }

    @Test
    void refusesWrongDaysBrokenPricesAndUnexpectedEntries() throws IOException {
        String ok = String.join("\n", HEADER, line(DAY, "INFY", "EQ", "10.00", "12.00", "9.00", "11.00", "10.00"));
        // An entry with another name (a path-traversal name included) is never read, let alone written anywhere.
        assertThatThrownBy(() -> BhavcopyClient.parseZip(zip("../../evil.csv", ok), DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class);
        assertThatThrownBy(() -> BhavcopyClient.parseCsv(String.join("\n", HEADER,
                line(DAY.minusDays(1), "INFY", "EQ", "10.00", "12.00", "9.00", "11.00", "10.00")), DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class); // the file is for another day
        assertThatThrownBy(() -> BhavcopyClient.parseCsv(String.join("\n", HEADER,
                line(DAY, "INFY", "EQ", "10.00", "10.50", "9.00", "11.00", "10.00")), DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class); // close above high
        assertThatThrownBy(() -> BhavcopyClient.parseCsv(String.join("\n", HEADER,
                line(DAY, "INFY", "EQ", "-1.00", "12.00", "9.00", "11.00", "10.00")), DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class);
        assertThatThrownBy(() -> BhavcopyClient.parseCsv(String.join("\n", HEADER,
                line(DAY, "INFY", "EQ", "1e9", "12.00", "9.00", "11.00", "10.00")), DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class);
        assertThatThrownBy(() -> BhavcopyClient.parseCsv("TradDt,TckrSymb\n", DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class); // a changed format fails loudly
    }

    @Test
    void anOversizedFileIsRefusedBeforeItIsRead() throws IOException {
        String huge = HEADER + "\n" + "x".repeat(BhavcopyClient.MAX_CSV_BYTES + 1);
        assertThatThrownBy(() -> BhavcopyClient.parseZip(zip(BhavcopyClient.fileName(DAY), huge), DAY, Set.of("INFY")))
                .isInstanceOf(BhavcopyException.class).hasMessageContaining("larger than");
    }
}
