package com.quantgen.trading.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.trading.api.TradingDtos.InstrumentDto;
import com.quantgen.trading.api.TradingDtos.PricePointDto;
import com.quantgen.trading.service.MarketService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.5 market data. Live updates: STOMP /ws, topic /topic/prices. */
@RestController
@RequestMapping("/api/v1/market/instruments")
@Tag(name = "Trading")
public class MarketController {

    private final MarketService market;

    public MarketController(MarketService market) {
        this.market = market;
    }

    @GetMapping
    public List<InstrumentDto> list() {
        return market.list();
    }

    @GetMapping("/{symbol}")
    public InstrumentDto get(@PathVariable String symbol) {
        return market.get(symbol);
    }

    @GetMapping("/{symbol}/history")
    @Operation(summary = "Price history, oldest first. 1H: 15s buckets, 1D: 5min, 1W: 30min (last price per bucket).")
    public List<PricePointDto> history(@PathVariable String symbol,
                                       @RequestParam(defaultValue = "1D") String range) {
        return market.history(symbol, MarketService.Range.parse(range));
    }
}
