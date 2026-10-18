package com.quantgen.trading.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.trading.api.TradingDtos.OrderDto;
import com.quantgen.trading.service.BacktestService;
import com.quantgen.trading.service.BacktestService.BacktestDto;
import com.quantgen.trading.service.BacktestService.BacktestRequest;
import com.quantgen.trading.service.OrderBook;
import com.quantgen.trading.service.TaxReportService;
import com.quantgen.trading.service.WatchlistService;
import com.quantgen.trading.service.WatchlistService.Direction;
import com.quantgen.trading.service.WatchlistService.PriceAlertDto;
import com.quantgen.trading.service.WatchlistService.WatchlistDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.9 Trading 2.0: order cancel, watchlists, price alerts, backtests, tax P&L. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Trading")
public class TradingToolsController {

    public record WatchlistRequest(@NotBlank String name) {
    }

    public record AlertRequest(@NotBlank String symbol, @NotNull Direction direction,
                               @NotNull @MoneyAmount(min = "0.01", max = "10000000.00") BigDecimal price) {
    }

    private final OrderBook orderBook;
    private final WatchlistService watchlists;
    private final BacktestService backtests;
    private final TaxReportService tax;

    public TradingToolsController(OrderBook orderBook, WatchlistService watchlists, BacktestService backtests,
                                  TaxReportService tax) {
        this.orderBook = orderBook;
        this.watchlists = watchlists;
        this.backtests = backtests;
        this.tax = tax;
    }

    @PostMapping("/orders/{id}/cancel")
    @Operation(summary = "Cancel an OPEN limit or stop order; its cash hold or reserved shares are released")
    public OrderDto cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return orderBook.cancel(user.id(), id);
    }

    @GetMapping("/watchlists")
    public List<WatchlistDto> watchlists(@AuthenticationPrincipal AuthUser user) {
        return watchlists.list(user.id());
    }

    @PostMapping("/watchlists")
    @Operation(summary = "Create a watchlist (at most 5)")
    public ResponseEntity<WatchlistDto> createWatchlist(@AuthenticationPrincipal AuthUser user,
                                                        @Valid @RequestBody WatchlistRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(watchlists.create(user.id(), request.name()));
    }

    @DeleteMapping("/watchlists/{id}")
    public ResponseEntity<Void> deleteWatchlist(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        watchlists.delete(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/watchlists/{id}/symbols/{symbol}")
    @Operation(summary = "Add a symbol (at most 50 per watchlist; adding one already there is a no-op)")
    public WatchlistDto addSymbol(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                  @PathVariable String symbol) {
        return watchlists.addSymbol(user.id(), id, symbol);
    }

    @DeleteMapping("/watchlists/{id}/symbols/{symbol}")
    public WatchlistDto removeSymbol(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                     @PathVariable String symbol) {
        return watchlists.removeSymbol(user.id(), id, symbol);
    }

    @GetMapping("/price-alerts")
    public List<PriceAlertDto> alerts(@AuthenticationPrincipal AuthUser user) {
        return watchlists.alerts(user.id());
    }

    @PostMapping("/price-alerts")
    @Operation(summary = "A one-shot alert when the price crosses a level (at most 20 active)")
    public ResponseEntity<PriceAlertDto> createAlert(@AuthenticationPrincipal AuthUser user,
                                                     @Valid @RequestBody AlertRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(watchlists.createAlert(user.id(), request.symbol(),
                request.direction(), request.price()));
    }

    @DeleteMapping("/price-alerts/{id}")
    public ResponseEntity<Void> deleteAlert(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        watchlists.deleteAlert(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/backtests")
    @Operation(summary = "Backtest a strategy over simulated daily prices (D-035); the last 20 are kept")
    public ResponseEntity<BacktestDto> backtest(@AuthenticationPrincipal AuthUser user,
                                                @RequestBody BacktestRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(backtests.run(user.id(), request));
    }

    @GetMapping("/backtests")
    public List<BacktestDto> backtests(@AuthenticationPrincipal AuthUser user) {
        return backtests.list(user.id());
    }

    @GetMapping("/reports/tax-pnl")
    @Operation(summary = "Realized gains for a financial year (FIFO lots, short/long term), as JSON or CSV")
    public ResponseEntity<?> taxPnl(@AuthenticationPrincipal AuthUser user,
                                    @RequestParam(required = false) String fy,
                                    @RequestParam(defaultValue = "json") String format) {
        TaxReportService.TaxPnlDto report = tax.report(user.id(), fy);
        return switch (format) {
            case "json" -> ResponseEntity.ok(report);
            case "csv" -> ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("tax-pnl-" + report.fy() + ".csv").build().toString())
                    .body(tax.csv(report));
            default -> throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "format", "must be json or csv");
        };
    }
}
