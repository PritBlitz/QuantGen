package com.quantgen.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.quantgen.loan.service.EmiCalculator;
import com.quantgen.loan.service.EmiCalculator.Row;
import com.quantgen.loan.service.LoanService;

class LoanUnitTest {

    private static final BigDecimal RATE = LoanService.ANNUAL_RATE;

    @Test
    void emiMatchesTheAmortizationFormula() {
        // 1,00,000 at 12% p.a. over 12 months: 100000 * 0.01 * 1.01^12 / (1.01^12 - 1) = 8884.8788… → 8884.88
        assertThat(EmiCalculator.emi(new BigDecimal("100000"), RATE, 12)).isEqualByComparingTo("8884.88");
        // 5,00,000 over 60 months → 11122.22
        assertThat(EmiCalculator.emi(new BigDecimal("500000"), RATE, 60)).isEqualByComparingTo("11122.22");
        assertThat(EmiCalculator.emi(new BigDecimal("10000"), RATE, 6)).isEqualByComparingTo("1725.48");
    }

    @Test
    void scheduleSumsExactlyAndLastInstalmentAbsorbsRounding() {
        List<Row> rows = EmiCalculator.schedule(new BigDecimal("100000"), RATE, 12, LocalDate.of(2026, 11, 16));
        assertThat(rows).hasSize(12);
        assertThat(rows.getFirst().interest()).isEqualByComparingTo("1000.00");
        assertThat(rows.getFirst().principal()).isEqualByComparingTo("7884.88");
        rows.subList(0, 11).forEach(r -> assertThat(r.emi()).isEqualByComparingTo("8884.88"));
        BigDecimal principal = rows.stream().map(Row::principal).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(principal).isEqualByComparingTo("100000.00");
        rows.forEach(r -> assertThat(r.emi()).isEqualByComparingTo(r.principal().add(r.interest())));

        EmiCalculator.Quote quote = EmiCalculator.quote(new BigDecimal("100000"), RATE, 12);
        BigDecimal interest = rows.stream().map(Row::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(quote.totalInterest()).isEqualByComparingTo(interest);
        assertThat(quote.totalPayable()).isEqualByComparingTo(interest.add(new BigDecimal("100000")));
    }

    @Test
    void scheduleInvariantsHoldAcrossTheWholeProductRange() {
        for (int months = 6; months <= 60; months++) {
            for (String p : new String[] {"10000", "10000.01", "123456.78", "333333.33", "999999.99", "1000000"}) {
                BigDecimal principal = new BigDecimal(p);
                List<Row> rows = EmiCalculator.schedule(principal, RATE, months, LocalDate.of(2026, 1, 31));
                BigDecimal emi = rows.getFirst().emi();
                BigDecimal sum = BigDecimal.ZERO;
                for (Row r : rows) {
                    assertThat(r.principal().signum()).isPositive();
                    assertThat(r.interest().signum()).isNotNegative();
                    assertThat(r.interest().stripTrailingZeros().scale()).isLessThanOrEqualTo(2);
                    sum = sum.add(r.principal());
                }
                assertThat(sum).as("principal sum for %s over %d", p, months).isEqualByComparingTo(principal);
                // The last instalment differs from the EMI by rounding only.
                assertThat(rows.getLast().emi().subtract(emi).abs()).isLessThan(BigDecimal.ONE);
            }
        }
    }

    @Test
    void dueDatesStepMonthlyWithoutMonthEndDrift() {
        List<Row> rows = EmiCalculator.schedule(new BigDecimal("10000"), RATE, 6, LocalDate.of(2027, 1, 31));
        assertThat(rows.stream().map(Row::dueDate).toList()).containsExactly(
                LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31),
                LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 31), LocalDate.of(2027, 6, 30));
    }
}
