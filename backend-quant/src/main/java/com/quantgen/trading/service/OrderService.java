package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.metrics.QgMetrics;
import com.quantgen.common.money.Money;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.trading.api.TradingDtos.OrderDto;
import com.quantgen.trading.api.TradingDtos.OrderRequest;
import com.quantgen.trading.domain.Holding;
import com.quantgen.trading.domain.HoldingRepository;
import com.quantgen.trading.domain.Instrument;
import com.quantgen.trading.domain.InstrumentRepository;
import com.quantgen.trading.domain.Order;
import com.quantgen.trading.domain.OrderRepository;
import com.quantgen.trading.domain.OrderSide;
import com.quantgen.trading.domain.OrderStatus;
import com.quantgen.trading.domain.OrderType;
import com.quantgen.trading.domain.OrderValidity;

/**
 * F-TRD-3/4 market orders and F-TRD-7 limit / stop-loss orders. The cash leg (through BROKERAGE_CLEARING) and the
 * holding leg happen in one transaction. Lock order: idempotency key → holding row → instrument (FOR SHARE) → ledger
 * rows. A limit or stop order whose price is already reached fills at once at the current price (which is at
 * least as good as the limit); otherwise it is OPEN: a BUY holds {@code qty × limit} of cash, a SELL reserves the
 * shares, and {@link OrderBook} fills, cancels or expires it later.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final HoldingRepository holdings;
    private final InstrumentRepository instruments;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final QgMetrics metrics;
    private final Clock clock;

    public OrderService(OrderRepository orders, HoldingRepository holdings, InstrumentRepository instruments,
                        AccountService accounts, LedgerService ledger, IdempotencyService idempotency, QgMetrics metrics,
                        Clock clock) {
        this.orders = orders;
        this.holdings = holdings;
        this.instruments = instruments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Result<OrderDto> place(UUID userId, UUID idempotencyKey, String fingerprint,
                                                     OrderRequest req) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, OrderDto.class,
                () -> doPlace(userId, req));
    }

    private OrderDto doPlace(UUID userId, OrderRequest req) {
        AccountRef account = accounts.requireOwned(userId, req.accountId());
        if (!account.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountId", "trade from a savings or checking account");
        }
        if (account.status() == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot trade");
        }
        if (account.status() != AccountStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Account is closed");
        }
        String symbol = MarketService.normalize(req.symbol());
        // Not findBySymbol: a managed Instrument would make the FOR SHARE read below return that stale copy, and a
        // tick in between would fail its version check (409 to the customer).
        if (!instruments.existsBySymbol(symbol)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "symbol", "unknown instrument");
        }
        long quantity = req.quantity();
        OrderType type = req.orderType() == null ? OrderType.MARKET : req.orderType();
        checkTerms(type, req);

        holdings.ensureExists(userId, symbol);
        Holding holding = holdings.lockByUserIdAndSymbol(userId, symbol).orElseThrow();
        if (req.side() == OrderSide.SELL && holding.available() < quantity) {
            throw new ApiException(ErrorCode.INSUFFICIENT_HOLDINGS, "You have " + holding.available() + " " + symbol
                    + " free to sell (" + holding.getReservedQty() + " reserved by open orders); cannot sell " + quantity);
        }

        // The fill price is read inside this transaction and pinned until commit.
        Instrument instrument = instruments.findBySymbolForShare(symbol).orElseThrow();
        BigDecimal price = instrument.getPrice();
        if (!OrderBook.reached(type, req.side(), price, req.limitPrice(), req.triggerPrice())) {
            return openOrder(userId, account, symbol, quantity, type, req, holding);
        }
        BigDecimal total = Money.normalize(price.multiply(BigDecimal.valueOf(quantity)));
        String description = req.side() + " " + quantity + " " + symbol + " @ " + Money.toJson(price);

        JournalRequest.Builder journal = JournalRequest.builder(JournalType.TRADE, description, userId);
        BigDecimal realized = null;
        if (req.side() == OrderSide.BUY) {
            journal.debit(account.id(), total, symbol).credit(SystemAccount.BROKERAGE_CLEARING.id(), total, null);
            holding.buy(quantity, total);
        } else {
            journal.debit(SystemAccount.BROKERAGE_CLEARING.id(), total, null).credit(account.id(), total, symbol);
            realized = holding.sell(quantity, total);
        }
        PostedEntry entry = ledger.post(journal.build()); // INSUFFICIENT_FUNDS rolls the holding change back too
        holdings.flush();

        Order filled = Order.filled(userId, account.id(), symbol, req.side(), quantity, price, total, realized,
                entry.journalEntryId());
        filled.setTerms(type, req.limitPrice(), req.triggerPrice(), type == OrderType.MARKET ? null : validity(req));
        Order order = orders.saveAndFlush(filled);
        metrics.orderFilled(req.side().name());
        return toDto(order);
    }

    private static void checkTerms(OrderType type, OrderRequest req) {
        switch (type) {
            case MARKET -> {
                if (req.limitPrice() != null || req.triggerPrice() != null || req.validity() != null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, req.limitPrice() != null ? "limitPrice"
                            : req.triggerPrice() != null ? "triggerPrice" : "validity", "not allowed on a MARKET order");
                }
            }
            case LIMIT -> {
                if (req.limitPrice() == null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "limitPrice", "is required for a LIMIT order");
                }
                if (req.triggerPrice() != null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "triggerPrice", "not allowed on a LIMIT order");
                }
            }
            case STOP_LOSS -> {
                if (req.side() != OrderSide.SELL) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "side", "a STOP_LOSS order must be a SELL");
                }
                if (req.triggerPrice() == null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "triggerPrice",
                            "is required for a STOP_LOSS order");
                }
                if (req.limitPrice() != null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "limitPrice",
                            "not allowed on a STOP_LOSS order");
                }
            }
        }
    }

    private static OrderValidity validity(OrderRequest req) {
        return req.validity() == null ? OrderValidity.DAY : req.validity();
    }

    private OrderDto openOrder(UUID userId, AccountRef account, String symbol, long quantity, OrderType type,
                               OrderRequest req, Holding holding) {
        OrderValidity validity = validity(req);
        Instant expiresAt = clock.instant().plus(validity == OrderValidity.DAY ? OrderBook.DAY : OrderBook.GTC);
        UUID holdId = null;
        if (req.side() == OrderSide.BUY) {
            BigDecimal reserve = Money.normalize(req.limitPrice().multiply(BigDecimal.valueOf(quantity)));
            // The hold outlives the order by a day; OrderBook releases it explicitly when the order ends.
            holdId = ledger.placeHold(account.id(), reserve, "ORDER", symbol, expiresAt.plus(OrderBook.DAY));
        } else {
            holding.reserve(quantity);
            holdings.flush();
        }
        Order order = orders.saveAndFlush(Order.open(userId, account.id(), symbol, req.side(), quantity, type,
                req.limitPrice() == null ? null : Money.normalize(req.limitPrice()),
                req.triggerPrice() == null ? null : Money.normalize(req.triggerPrice()), validity, expiresAt, holdId));
        return toDto(order);
    }

    @Transactional(readOnly = true)
    public PageDto<OrderDto> list(UUID userId, Pageable pageable) {
        return PageDto.of(orders.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable), OrderService::toDto);
    }

    @Transactional(readOnly = true)
    public long filledSince(Instant since) {
        return orders.countByStatusAndCreatedAtGreaterThanEqual(OrderStatus.FILLED, since);
    }

    static OrderDto toDto(Order o) {
        return new OrderDto(o.getId(), o.getSymbol(), o.getSide(), o.getQuantity(), o.getPrice(), o.getTotal(),
                o.getStatus(), o.getCreatedAt(), o.getOrderType(), o.getLimitPrice(), o.getTriggerPrice(),
                o.getValidity(), o.getFilledAt());
    }
}
