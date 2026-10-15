package com.quantgen.trading.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.trading.api.TradingDtos.OrderDto;
import com.quantgen.trading.api.TradingDtos.OrderRequest;
import com.quantgen.trading.api.TradingDtos.PortfolioDto;
import com.quantgen.trading.service.OrderService;
import com.quantgen.trading.service.PortfolioService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.5 orders and portfolio. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Trading")
public class OrderController {

    private final OrderService orders;
    private final PortfolioService portfolio;
    private final IdempotencyService idempotency;

    public OrderController(OrderService orders, PortfolioService portfolio, IdempotencyService idempotency) {
        this.orders = orders;
        this.portfolio = portfolio;
        this.idempotency = idempotency;
    }

    @PostMapping("/orders")
    @Operation(summary = "Market order, filled immediately at the current price. Requires Idempotency-Key.")
    public ResponseEntity<OrderDto> place(@AuthenticationPrincipal AuthUser user,
                                          @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                          @Valid @RequestBody OrderRequest request, HttpServletRequest http) {
        return Idempotent.created(orders.place(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request));
    }

    @GetMapping("/orders")
    public PageDto<OrderDto> list(@AuthenticationPrincipal AuthUser user,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        return orders.list(user.id(), PageDto.pageable(page, size));
    }

    @GetMapping("/portfolio")
    public PortfolioDto portfolio(@AuthenticationPrincipal AuthUser user) {
        return portfolio.portfolio(user.id());
    }
}
