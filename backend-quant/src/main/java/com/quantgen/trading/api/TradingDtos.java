package com.quantgen.trading.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import com.quantgen.common.money.MoneyAmount;
import com.quantgen.trading.domain.OrderSide;
import com.quantgen.trading.domain.OrderStatus;
import com.quantgen.trading.domain.OrderType;
import com.quantgen.trading.domain.OrderValidity;

/** PRD §7.5 request and response bodies. */
public final class TradingDtos {

    private TradingDtos() {
    }

    public record InstrumentDto(String symbol, String name, String sector, BigDecimal price, BigDecimal changePct24h,
                                Instant updatedAt) {
    }

    public record PricePointDto(Instant t, BigDecimal price) {
    }

    /**
     * F-TRD-4: whole-number quantities from 1 to 10,000. v2 (F-TRD-7): {@code orderType} defaults to MARKET; LIMIT
     * needs {@code limitPrice}, STOP_LOSS (sell only) needs {@code triggerPrice}; {@code validity} defaults to DAY.
     */
    public record OrderRequest(
            @NotNull UUID accountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{1,10}$", message = "must be 1-10 letters") String symbol,
            @NotNull OrderSide side,
            @NotNull @Min(1) @Max(10_000) Long quantity,
            OrderType orderType,
            @MoneyAmount(min = "0.01", max = "10000000.00") BigDecimal limitPrice,
            @MoneyAmount(min = "0.01", max = "10000000.00") BigDecimal triggerPrice,
            OrderValidity validity) {

        public OrderRequest(UUID accountId, String symbol, OrderSide side, Long quantity) {
            this(accountId, symbol, side, quantity, null, null, null, null);
        }
    }

    public record OrderDto(UUID id, String symbol, OrderSide side, long quantity, BigDecimal price, BigDecimal total,
                           OrderStatus status, Instant createdAt, OrderType orderType, BigDecimal limitPrice,
                           BigDecimal triggerPrice, OrderValidity validity, Instant filledAt) {
    }

    public record HoldingDto(String symbol, String name, long quantity, BigDecimal avgCost, BigDecimal price,
                             BigDecimal marketValue, BigDecimal unrealizedPnl, BigDecimal unrealizedPnlPct) {
    }

    public record PortfolioDto(List<HoldingDto> holdings, BigDecimal totalMarketValue, BigDecimal totalCost,
                               BigDecimal totalUnrealizedPnl, BigDecimal totalRealizedPnl) {
    }
}
