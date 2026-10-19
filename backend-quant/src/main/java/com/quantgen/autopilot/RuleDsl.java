package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Autopilot DSL v1 (PRD 12.7), as stored after validation. Absent fields are left out of the JSON. Amounts are
 * {@link BigDecimal}s here and money strings on the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RuleDsl(int dslVersion, Trigger trigger, List<Condition> conditions, List<Action> actions, Caps caps) {

    public static final int VERSION = 1;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Trigger(String type, UUID accountId, BigDecimal minAmount, String counterpartyContains,
                          String transactionType, String frequency, Integer dayOfMonth, String weekday,
                          BigDecimal threshold, String symbol, String direction, BigDecimal price) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(String type, UUID accountId, BigDecimal amount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Amount(String mode, BigDecimal value) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Action(String type, UUID fromAccountId, UUID toAccountId, UUID toBeneficiaryId, UUID accountId,
                         String symbol, Amount amount, String message) {
    }

    public record Caps(BigDecimal perRun, BigDecimal perMonth) {
    }
}
