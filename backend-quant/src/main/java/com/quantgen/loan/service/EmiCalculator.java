package com.quantgen.loan.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.quantgen.common.money.Money;

/**
 * F-LOAN-3 amortization (ARCHITECTURE §7). EMI = P·r·(1+r)^n / ((1+r)^n − 1) with r = annual rate / 12,
 * rounded half-even to paise. Each instalment's interest is outstanding × r rounded to paise; the last
 * instalment takes whatever principal is left, so the principals always sum to exactly P.
 */
public final class EmiCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private EmiCalculator() {
    }

    public record Row(int installmentNo, LocalDate dueDate, BigDecimal emi, BigDecimal principal, BigDecimal interest) {
    }

    public record Quote(BigDecimal emi, BigDecimal totalInterest, BigDecimal totalPayable) {
    }

    /** Monthly rate as a fraction, e.g. 12% p.a. → 0.01. */
    static BigDecimal monthlyRate(BigDecimal annualRatePct) {
        return annualRatePct.divide(HUNDRED, MC).divide(TWELVE, MC);
    }

    public static BigDecimal emi(BigDecimal principal, BigDecimal annualRatePct, int months) {
        if (principal.signum() <= 0 || months <= 0) {
            throw new IllegalArgumentException("principal and months must be positive");
        }
        BigDecimal r = monthlyRate(annualRatePct);
        if (r.signum() == 0) {
            return Money.roundToPaise(principal.divide(BigDecimal.valueOf(months), MC));
        }
        BigDecimal growth = BigDecimal.ONE.add(r).pow(months, MC);
        BigDecimal emi = principal.multiply(r, MC).multiply(growth, MC).divide(growth.subtract(BigDecimal.ONE), MC);
        return Money.roundToPaise(emi);
    }

    /**
     * The full schedule. Instalment k is due {@code firstDueDate} plus (k − 1) months, computed from the
     * first date each time so month-end dates don't drift (31 Jan → 28/29 Feb → 31 Mar).
     */
    public static List<Row> schedule(BigDecimal principal, BigDecimal annualRatePct, int months, LocalDate firstDueDate) {
        BigDecimal emi = emi(principal, annualRatePct, months);
        BigDecimal r = monthlyRate(annualRatePct);
        BigDecimal outstanding = Money.normalize(principal);
        List<Row> rows = new ArrayList<>(months);
        for (int k = 1; k <= months; k++) {
            BigDecimal interest = Money.roundToPaise(outstanding.multiply(r, MC));
            BigDecimal principalPart;
            BigDecimal instalment;
            if (k == months) {
                principalPart = outstanding;
                instalment = principalPart.add(interest);
            } else {
                principalPart = emi.subtract(interest);
                instalment = emi;
            }
            outstanding = outstanding.subtract(principalPart);
            rows.add(new Row(k, firstDueDate == null ? null : firstDueDate.plusMonths(k - 1L), instalment,
                    principalPart, interest));
        }
        return rows;
    }

    public static Quote quote(BigDecimal principal, BigDecimal annualRatePct, int months) {
        List<Row> rows = schedule(principal, annualRatePct, months, null);
        BigDecimal interest = rows.stream().map(Row::interest).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Quote(rows.getFirst().emi(), interest, Money.normalize(principal).add(interest));
    }
}
