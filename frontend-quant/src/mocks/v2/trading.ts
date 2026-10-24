/**
 * MSW handlers: Trading 2.0 (§12.9). Limit / stop orders rest as OPEN with a hold (buy) or
 * reserved shares (sell) and fill when a later tick crosses; market orders fall through to the
 * v1 handler. Also watchlists, price alerts, backtests and the tax P&L report.
 */
import { delay, http } from 'msw'
import type { BacktestDto, OrderDto, StrategyDsl } from '@/api/types'
import { BASE, db, error, idempotent, inr, isMoney, notify } from '../handlers'
import {
  addDays,
  available,
  Decimal,
  invalid,
  json,
  money,
  move,
  notFound,
  ownAccount,
  requireUser,
} from './common'
import { prng } from './state'

type MockOrder = OrderDto & {
  userId: string
  accountId?: string
  holdId?: string
  expiresAtMs?: number
}

/** Fill or expire resting orders against the current prices (called lazily on reads). */
function matchOpenOrders() {
  for (const o of db.orders as MockOrder[]) {
    if (o.status !== 'OPEN') continue
    if (o.expiresAtMs && o.expiresAtMs < Date.now()) {
      o.status = 'EXPIRED'
      const h = db.v2.holds.find((x) => x.id === o.holdId)
      if (h) h.status = 'RELEASED'
      continue
    }
    const inst = db.instruments.get(o.symbol)
    if (!inst || !o.accountId) continue
    const price = new Decimal(inst.price)
    const limit = o.limitPrice ? new Decimal(o.limitPrice) : null
    const trigger = o.triggerPrice ? new Decimal(o.triggerPrice) : null
    const fill =
      o.orderType === 'LIMIT'
        ? o.side === 'BUY'
          ? price.lte(limit!)
          : price.gte(limit!)
        : o.orderType === 'STOP_LOSS'
          ? price.lte(trigger!)
          : false
    if (!fill) continue
    const fillPrice = o.orderType === 'LIMIT' ? limit! : price
    const total = fillPrice.times(o.quantity)
    const acc = db.accounts.get(o.accountId)
    if (!acc) continue
    const hold = db.v2.holds.find((x) => x.id === o.holdId)
    if (hold) hold.status = 'CAPTURED'
    const holding = db.holdings.find((h) => h.userId === o.userId && h.symbol === o.symbol)
    if (o.side === 'BUY') {
      move(acc, null, total, 'TRADE', `Bought ${o.quantity} × ${o.symbol} (limit)`, '')
      if (holding) {
        holding.avgCost = new Decimal(holding.avgCost)
          .times(holding.quantity)
          .plus(total)
          .div(holding.quantity + o.quantity)
          .toFixed(4)
        holding.quantity += o.quantity
      } else
        db.holdings.push({
          userId: o.userId,
          symbol: o.symbol,
          quantity: o.quantity,
          avgCost: fillPrice.toFixed(4),
          realizedPnl: '0',
        })
    } else if (holding) {
      acc.balance = new Decimal(acc.balance).plus(total).toFixed(4)
      holding.realizedPnl = new Decimal(holding.realizedPnl)
        .plus(fillPrice.minus(holding.avgCost).times(o.quantity))
        .toFixed(4)
      holding.quantity -= o.quantity
    }
    o.status = 'FILLED'
    o.price = money(fillPrice)
    o.total = money(total)
    o.filledAt = new Date().toISOString()
    notify(
      o.userId,
      'PRICE_ALERT',
      'Order filled',
      `${o.side === 'BUY' ? 'Bought' : 'Sold'} ${o.quantity} × ${o.symbol} at ${inr(fillPrice)}.`,
    )
  }
  for (const a of db.v2.alerts) {
    if (a.status !== 'ACTIVE') continue
    const p = db.instruments.get(a.symbol)?.price
    if (!p) continue
    if (
      (a.direction === 'ABOVE' && new Decimal(p).gte(a.price)) ||
      (a.direction === 'BELOW' && new Decimal(p).lte(a.price))
    ) {
      a.status = 'TRIGGERED'
      a.triggeredAt = new Date().toISOString()
      notify(
        a.userId,
        'PRICE_ALERT',
        `${a.symbol} ${a.direction === 'ABOVE' ? 'rose above' : 'fell below'} ${inr(a.price)}`,
        `${a.symbol} is now ${inr(p)}.`,
      )
    }
  }
}

function runBacktest(
  strategy: StrategyDsl,
  symbols: string[],
  from: string,
  to: string,
  initialCash: string,
): Omit<BacktestDto, 'id' | 'createdAt'> {
  const days = Math.max(10, Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000))
  const rnd = prng(days * 13 + symbols.join('').length * 7 + strategy.type.length)
  const prices = symbols.map((s) => {
    let p = new Decimal(db.instruments.get(s)?.price ?? '1000').times(0.85)
    return Array.from(
      { length: days },
      () => (p = p.times(1 + (rnd() - 0.48) * 0.035).toDecimalPlaces(2)),
    )
  })
  let cash = new Decimal(initialCash)
  const qty = symbols.map(() => 0)
  const equity: BacktestDto['equity'] = []
  const trades: BacktestDto['trades'] = []
  let peak = cash
  let maxDd = new Decimal(0)
  let wins = 0
  let closed = 0
  const entry = symbols.map(() => new Decimal(0))
  for (let d = 0; d < days; d++) {
    const date = addDays(from, d)
    symbols.forEach((sym, k) => {
      const series = prices[k]!
      const price = series[d]!
      const sma = (n: number) =>
        series
          .slice(Math.max(0, d - n + 1), d + 1)
          .reduce((s, x) => s.plus(x), new Decimal(0))
          .div(Math.min(n, d + 1))
      let signal: 'BUY' | 'SELL' | null = null
      if (strategy.type === 'SMA_CROSS' && d > strategy.slow)
        signal = sma(strategy.fast).gt(sma(strategy.slow)) ? 'BUY' : 'SELL'
      if (strategy.type === 'RSI' && d > strategy.period)
        signal = rnd() < 0.08 ? 'BUY' : rnd() < 0.08 ? 'SELL' : null
      if (strategy.type === 'BUY_THE_DIP' && d > 5) {
        const drop = series[d - 5]!.minus(price)
          .div(series[d - 5]!)
          .times(100)
        signal = drop.gte(strategy.dropPct)
          ? 'BUY'
          : qty[k]! > 0 &&
              price.gte(entry[k]!.times(new Decimal(strategy.takeProfitPct).div(100).plus(1)))
            ? 'SELL'
            : null
      }
      if (strategy.type === 'REBALANCE' && d % strategy.everyDays === 0) signal = 'BUY'
      const budget = cash.div(symbols.length).toDecimalPlaces(2)
      if (signal === 'BUY' && qty[k] === 0 && budget.gte(price)) {
        const n = budget.div(price).toDecimalPlaces(0, Decimal.ROUND_DOWN).toNumber()
        if (n > 0) {
          cash = cash.minus(price.times(n))
          qty[k] = n
          entry[k] = price
          trades.push({ t: date, symbol: sym, side: 'BUY', quantity: n, price: money(price) })
        }
      } else if (signal === 'SELL' && qty[k]! > 0) {
        cash = cash.plus(price.times(qty[k]!))
        closed += 1
        if (price.gt(entry[k]!)) wins += 1
        trades.push({ t: date, symbol: sym, side: 'SELL', quantity: qty[k]!, price: money(price) })
        qty[k] = 0
      }
    })
    const value = symbols.reduce((s, _sym, k) => s.plus(prices[k]![d]!.times(qty[k]!)), cash)
    equity.push({ t: date, value: money(value) })
    if (value.gt(peak)) peak = value
    const dd = peak.minus(value).div(peak).times(100)
    if (dd.gt(maxDd)) maxDd = dd
  }
  const final = new Decimal(equity.at(-1)!.value)
  return {
    strategy,
    symbols,
    from,
    to,
    initialCash: money(initialCash),
    finalValue: money(final),
    returnPct: final.minus(initialCash).div(initialCash).times(100).toDecimalPlaces(2).toFixed(2),
    maxDrawdownPct: maxDd.toDecimalPlaces(2).toFixed(2),
    winRatePct: closed
      ? new Decimal(wins).div(closed).times(100).toDecimalPlaces(2).toFixed(2)
      : '0.00',
    equity,
    trades: trades.slice(-60),
  }
}

export const tradingHandlers = [
  http.post(`${BASE}/orders`, async ({ request }) => {
    const b = (await request.clone().json()) as Record<string, unknown>
    if (!b.orderType || b.orderType === 'MARKET') return undefined // v1 handler fills it now
    await delay(400)
    const r = requireUser(request)
    if (!r.user) return r.res
    const idem = idempotent(request, r.user.id, b)
    if (idem.early) return idem.early
    const acc = ownAccount(r.user.id, b.accountId)
    if (!acc) return notFound(request, 'Account')
    const inst = typeof b.symbol === 'string' ? db.instruments.get(b.symbol) : undefined
    if (!inst) return invalid(request, 'symbol', 'unknown symbol')
    const qty = b.quantity
    if (typeof qty !== 'number' || !Number.isInteger(qty) || qty < 1 || qty > 10000)
      return invalid(request, 'quantity', 'must be a whole number from 1 to 10000')
    if (b.side !== 'BUY' && b.side !== 'SELL')
      return invalid(request, 'side', 'must be BUY or SELL')
    if (b.orderType === 'STOP_LOSS' && b.side !== 'SELL')
      return invalid(request, 'side', 'stop-loss orders sell')
    const priceField = b.orderType === 'LIMIT' ? 'limitPrice' : 'triggerPrice'
    if (!isMoney(b[priceField]) || new Decimal(String(b[priceField])).lte(0))
      return invalid(request, priceField, 'must be a price above 0')
    if (acc.status === 'FROZEN') return error(422, 'ACCOUNT_FROZEN', 'Account is frozen', request)
    const price = new Decimal(String(b[priceField]))
    let holdId: string | undefined
    if (b.side === 'BUY') {
      const need = price.times(qty)
      if (available(acc).lt(need))
        return error(422, 'INSUFFICIENT_FUNDS', 'Insufficient funds', request)
      holdId = crypto.randomUUID()
      db.v2.holds.push({
        id: holdId,
        accountId: acc.id,
        amount: money(need),
        reason: 'LIMIT_ORDER',
        status: 'ACTIVE',
      })
    } else {
      const holding = db.holdings.find((h) => h.userId === r.user.id && h.symbol === inst.symbol)
      const reserved = (db.orders as MockOrder[])
        .filter(
          (o) =>
            o.userId === r.user.id &&
            o.symbol === inst.symbol &&
            o.side === 'SELL' &&
            o.status === 'OPEN',
        )
        .reduce((s, o) => s + o.quantity, 0)
      if (!holding || holding.quantity - reserved < qty)
        return error(422, 'INSUFFICIENT_HOLDINGS', 'Insufficient holdings', request)
    }
    const validity = b.validity === 'GTC' ? 'GTC' : 'DAY'
    const order: MockOrder = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      accountId: acc.id,
      holdId,
      symbol: inst.symbol,
      side: b.side,
      quantity: qty,
      price: money(price),
      total: money(price.times(qty)),
      status: 'OPEN',
      createdAt: new Date().toISOString(),
      orderType: b.orderType as 'LIMIT' | 'STOP_LOSS',
      limitPrice: b.orderType === 'LIMIT' ? money(price) : null,
      triggerPrice: b.orderType === 'STOP_LOSS' ? money(price) : null,
      validity,
      filledAt: null,
      expiresAtMs: Date.now() + (validity === 'GTC' ? 90 : 1) * 86_400_000,
    }
    db.orders.unshift(order)
    const { userId: _u, accountId: _a, holdId: _h, expiresAtMs: _e, ...out } = order
    idem.save(201, out)
    return json(out, 201)
  }),
  http.get(`${BASE}/orders`, () => {
    matchOpenOrders()
    return undefined // fall through to the v1 list (which now includes OPEN/FILLED/CANCELLED)
  }),
  http.get(`${BASE}/portfolio`, () => {
    matchOpenOrders()
    return undefined
  }),
  http.post(`${BASE}/orders/:id/cancel`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const o = (db.orders as MockOrder[]).find((x) => x.id === params.id && x.userId === r.user.id)
    if (!o) return notFound(request, 'Order')
    if (o.status !== 'OPEN')
      return error(409, 'INVALID_STATE', 'Only open orders can be cancelled', request)
    o.status = 'CANCELLED'
    const h = db.v2.holds.find((x) => x.id === o.holdId)
    if (h) h.status = 'RELEASED'
    const { userId: _u, accountId: _a, holdId: _h, expiresAtMs: _e, ...out } = o
    return json(out)
  }),

  // Watchlists
  http.get(`${BASE}/watchlists`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(
      db.v2.watchlists.filter((w) => w.userId === r.user.id).map(({ userId: _u, ...w }) => w),
    )
  }),
  http.post(`${BASE}/watchlists`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { name?: unknown }
    const name = typeof b.name === 'string' ? b.name.trim() : ''
    if (!name || name.length > 30) return invalid(request, 'name', 'must be 1–30 characters')
    if (db.v2.watchlists.filter((w) => w.userId === r.user.id).length >= 5)
      return error(422, 'LIMIT_EXCEEDED', 'At most 5 watchlists', request)
    const w = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      name,
      symbols: [],
      createdAt: new Date().toISOString(),
    }
    db.v2.watchlists.push(w)
    const { userId: _u, ...out } = w
    return json(out, 201)
  }),
  http.delete(`${BASE}/watchlists/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.watchlists.findIndex((w) => w.id === params.id && w.userId === r.user.id)
    if (i < 0) return notFound(request, 'Watchlist')
    db.v2.watchlists.splice(i, 1)
    return new Response(null, { status: 204 })
  }),
  http.post(`${BASE}/watchlists/:id/symbols/:symbol`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const w = db.v2.watchlists.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!w) return notFound(request, 'Watchlist')
    const sym = String(params.symbol)
    if (!db.instruments.has(sym)) return invalid(request, 'symbol', 'unknown symbol')
    if (w.symbols.length >= 50) return error(422, 'LIMIT_EXCEEDED', 'At most 50 symbols', request)
    if (!w.symbols.includes(sym)) w.symbols.push(sym)
    const { userId: _u, ...out } = w
    return json(out)
  }),
  http.delete(`${BASE}/watchlists/:id/symbols/:symbol`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const w = db.v2.watchlists.find((x) => x.id === params.id && x.userId === r.user.id)
    if (!w) return notFound(request, 'Watchlist')
    w.symbols = w.symbols.filter((s) => s !== params.symbol)
    return new Response(null, { status: 204 })
  }),

  // Price alerts
  http.get(`${BASE}/price-alerts`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    matchOpenOrders()
    return json(db.v2.alerts.filter((a) => a.userId === r.user.id).map(({ userId: _u, ...a }) => a))
  }),
  http.post(`${BASE}/price-alerts`, async ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as { symbol?: unknown; direction?: unknown; price?: unknown }
    if (typeof b.symbol !== 'string' || !db.instruments.has(b.symbol))
      return invalid(request, 'symbol', 'unknown symbol')
    if (b.direction !== 'ABOVE' && b.direction !== 'BELOW')
      return invalid(request, 'direction', 'must be ABOVE or BELOW')
    if (!isMoney(b.price) || new Decimal(b.price).lte(0))
      return invalid(request, 'price', 'must be a price above 0')
    if (db.v2.alerts.filter((a) => a.userId === r.user.id && a.status === 'ACTIVE').length >= 20)
      return error(422, 'LIMIT_EXCEEDED', 'At most 20 active alerts', request)
    const a = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      symbol: b.symbol,
      direction: b.direction as 'ABOVE' | 'BELOW',
      price: money(b.price),
      status: 'ACTIVE' as const,
      triggeredAt: null,
      createdAt: new Date().toISOString(),
    }
    db.v2.alerts.unshift(a)
    const { userId: _u, ...out } = a
    return json(out, 201)
  }),
  http.delete(`${BASE}/price-alerts/:id`, ({ request, params }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const i = db.v2.alerts.findIndex((a) => a.id === params.id && a.userId === r.user.id)
    if (i < 0) return notFound(request, 'Alert')
    db.v2.alerts.splice(i, 1)
    return new Response(null, { status: 204 })
  }),

  // Backtests
  http.post(`${BASE}/backtests`, async ({ request }) => {
    await delay(900)
    const r = requireUser(request)
    if (!r.user) return r.res
    const b = (await request.json()) as {
      strategy?: StrategyDsl
      symbols?: unknown
      from?: unknown
      to?: unknown
      initialCash?: unknown
    }
    const symbols = Array.isArray(b.symbols)
      ? b.symbols.filter((s): s is string => typeof s === 'string' && db.instruments.has(s))
      : []
    if (!symbols.length || symbols.length > 5)
      return invalid(request, 'symbols', 'pick 1–5 instruments')
    if (!b.strategy || !['SMA_CROSS', 'RSI', 'BUY_THE_DIP', 'REBALANCE'].includes(b.strategy.type))
      return invalid(request, 'strategy', 'unknown strategy')
    if (
      b.strategy.type === 'SMA_CROSS' &&
      !(b.strategy.fast >= 2 && b.strategy.slow > b.strategy.fast && b.strategy.slow <= 200)
    )
      return invalid(request, 'strategy.slow', 'slow must be greater than fast (2–200)')
    if (typeof b.from !== 'string' || typeof b.to !== 'string' || b.from >= b.to)
      return invalid(request, 'from', 'must be before to')
    if (
      !isMoney(b.initialCash) ||
      new Decimal(b.initialCash).lt(10000) ||
      new Decimal(b.initialCash).gt(10000000)
    )
      return invalid(request, 'initialCash', 'must be ₹10,000–₹1,00,00,000')
    const result = {
      id: crypto.randomUUID(),
      userId: r.user.id,
      createdAt: new Date().toISOString(),
      ...runBacktest(b.strategy, symbols, b.from, b.to, b.initialCash),
    }
    db.v2.backtests.unshift(result)
    db.v2.backtests = db.v2.backtests.slice(0, 20)
    const { userId: _u, ...out } = result
    return json(out, 201)
  }),
  http.get(`${BASE}/backtests`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    return json(
      db.v2.backtests.filter((x) => x.userId === r.user.id).map(({ userId: _u, ...x }) => x),
    )
  }),

  // Tax P&L (FY Apr–Mar)
  http.get(`${BASE}/reports/tax-pnl`, ({ request }) => {
    const r = requireUser(request)
    if (!r.user) return r.res
    const url = new URL(request.url)
    const fy = url.searchParams.get('fy') ?? '2026-27'
    if (!/^\d{4}-\d{2}$/.test(fy)) return invalid(request, 'fy', 'must look like 2026-27')
    const rows = [
      {
        symbol: 'TCS',
        buyDate: '2025-08-12',
        sellDate: '2026-06-03',
        quantity: 4,
        cost: '15240.0000',
        proceeds: '16480.5000',
        gain: '1240.5000',
        term: 'SHORT' as const,
      },
      {
        symbol: 'ITC',
        buyDate: '2025-03-02',
        sellDate: '2026-05-21',
        quantity: 20,
        cost: '9346.0000',
        proceeds: '9036.0000',
        gain: '-310.0000',
        term: 'LONG' as const,
      },
      {
        symbol: 'INFY',
        buyDate: '2026-01-10',
        sellDate: '2026-09-28',
        quantity: 6,
        cost: '10512.0000',
        proceeds: '11290.2000',
        gain: '778.2000',
        term: 'SHORT' as const,
      },
    ]
    const st = rows
      .filter((x) => x.term === 'SHORT')
      .reduce((s, x) => s.plus(x.gain), new Decimal(0))
    const lt = rows
      .filter((x) => x.term === 'LONG')
      .reduce((s, x) => s.plus(x.gain), new Decimal(0))
    if (url.searchParams.get('format') === 'csv') {
      const csv = `symbol,buy_date,sell_date,quantity,cost,proceeds,gain,term\n${rows.map((x) => [x.symbol, x.buyDate, x.sellDate, x.quantity, x.cost, x.proceeds, x.gain, x.term].join(',')).join('\n')}\n`
      return new Response(csv, { headers: { 'Content-Type': 'text/csv' } })
    }
    return json({
      fy,
      shortTermGains: money(st),
      longTermGains: money(lt),
      total: money(st.plus(lt)),
      rows,
    })
  }),
]
