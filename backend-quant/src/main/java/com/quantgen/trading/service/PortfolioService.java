package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.trading.api.TradingDtos.HoldingDto;
import com.quantgen.trading.api.TradingDtos.PortfolioDto;
import com.quantgen.trading.domain.Holding;
import com.quantgen.trading.domain.HoldingRepository;
import com.quantgen.trading.domain.Instrument;
import com.quantgen.trading.domain.InstrumentRepository;

/**
 * F-TRD-5. Per holding: marketValue = price × qty, unrealizedPnl = marketValue − cost basis,
 * unrealizedPnlPct = unrealizedPnl / cost basis × 100 (2 dp). totalRealizedPnl includes closed positions.
 */
@Service
public class PortfolioService {

    private final HoldingRepository holdings;
    private final InstrumentRepository instruments;

    public PortfolioService(HoldingRepository holdings, InstrumentRepository instruments) {
        this.holdings = holdings;
        this.instruments = instruments;
    }

    @Transactional(readOnly = true)
    public PortfolioDto portfolio(UUID userId) {
        Map<String, Instrument> bySymbol = instruments.findAll().stream()
                .collect(Collectors.toMap(Instrument::getSymbol, Function.identity()));
        List<HoldingDto> rows = new ArrayList<>();
        BigDecimal totalValue = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalRealized = BigDecimal.ZERO;
        for (Holding h : holdings.findByUserIdOrderBySymbolAsc(userId)) {
            totalRealized = totalRealized.add(h.getRealizedPnl());
            if (h.getQuantity() == 0) {
                continue;
            }
            Instrument instrument = bySymbol.get(h.getSymbol());
            BigDecimal price = instrument.getPrice();
            BigDecimal value = price.multiply(BigDecimal.valueOf(h.getQuantity()));
            BigDecimal unrealized = value.subtract(h.getCostBasis());
            BigDecimal pct = h.getCostBasis().signum() == 0
                    ? BigDecimal.ZERO
                    : unrealized.multiply(BigDecimal.valueOf(100)).divide(h.getCostBasis(), 2, RoundingMode.HALF_EVEN);
            rows.add(new HoldingDto(h.getSymbol(), instrument.getName(), h.getQuantity(),
                    h.averageCost().setScale(2, RoundingMode.HALF_EVEN), price, value, unrealized, pct));
            totalValue = totalValue.add(value);
            totalCost = totalCost.add(h.getCostBasis());
        }
        return new PortfolioDto(rows, totalValue, totalCost, totalValue.subtract(totalCost), totalRealized);
    }
}
