package com.quantgen.trading.service;

import java.math.BigDecimal;
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

/**
 * F-TRD-3/4: market orders. The cash leg (through BROKERAGE_CLEARING) and the holding leg happen in one
 * transaction. Lock order: idempotency key → holding row → instrument (FOR SHARE) → ledger rows.
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final HoldingRepository holdings;
    private final InstrumentRepository instruments;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public OrderService(OrderRepository orders, HoldingRepository holdings, InstrumentRepository instruments,
                        AccountService accounts, LedgerService ledger, IdempotencyService idempotency) {
        this.orders = orders;
        this.holdings = holdings;
        this.instruments = instruments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @Transactional
    public IdempotencyService.Result<OrderDto> place(UUID userId, UUID idempotencyKey, String fingerprint,
                                                     OrderRequest req) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, OrderDto.class,
                () -> doPlace(userId, req));
    }

    private OrderDto doPlace(UUID userId, OrderRequest req) {
        AccountRef account = accounts.requireOwned(userId, req.accountId());
        if (account.status() == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot trade");
        }
        if (account.status() != AccountStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Account is closed");
        }
        String symbol = MarketService.normalize(req.symbol());
        if (instruments.findBySymbol(symbol).isEmpty()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "symbol", "unknown instrument");
        }
        long quantity = req.quantity();

        holdings.ensureExists(userId, symbol);
        Holding holding = holdings.lockByUserIdAndSymbol(userId, symbol).orElseThrow();
        if (req.side() == OrderSide.SELL && holding.getQuantity() < quantity) {
            throw new ApiException(ErrorCode.INSUFFICIENT_HOLDINGS,
                    "You hold " + holding.getQuantity() + " " + symbol + "; cannot sell " + quantity);
        }

        // The fill price is read inside this transaction and pinned until commit.
        Instrument instrument = instruments.findBySymbolForShare(symbol).orElseThrow();
        BigDecimal price = instrument.getPrice();
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

        Order order = orders.saveAndFlush(Order.filled(userId, account.id(), symbol, req.side(), quantity, price, total,
                realized, entry.journalEntryId()));
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
                o.getStatus(), o.getCreatedAt());
    }
}
