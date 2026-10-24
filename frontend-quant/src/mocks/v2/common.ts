/**
 * Shared helpers for the v2 MSW handlers. They reuse the v1 helpers (error body, auth, ledger
 * postLine) so v2 mocks enforce the same rules as the real backend: ownership → 404, idempotency,
 * step-up on risky actions, money as Decimal.
 */
import { HttpResponse } from 'msw'
import type { AccountDto, TransactionDto, UserDto } from '@/api/types'
import { Decimal } from '@/lib/money'
import { db, error, last4, postLine, userFrom } from '../handlers'

export { Decimal }

export type Acc = AccountDto & { userId: string }

/** 401 response or the signed-in user. */
export function requireUser(
  request: Request,
): { user: UserDto; res: null } | { user: null; res: Response } {
  const user = userFrom(request)
  if (!user)
    return { user: null, res: error(401, 'UNAUTHORIZED', 'Authentication required', request) }
  return { user, res: null }
}

export function requireAdmin(
  request: Request,
): { user: UserDto; res: null } | { user: null; res: Response } {
  const r = requireUser(request)
  if (!r.user) return r
  if (r.user.role !== 'ADMIN')
    return { user: null, res: error(403, 'FORBIDDEN', 'Access denied', request) }
  return r
}

export const json = <T>(body: T, status = 200) =>
  HttpResponse.json(body as Record<string, unknown>, { status })

export const notFound = (request: Request, what = 'Resource') =>
  error(404, 'NOT_FOUND', `${what} not found`, request)

export const invalid = (request: Request, field: string, message: string) =>
  error(400, 'VALIDATION_FAILED', 'Request validation failed', request, [{ field, message }])

export function ownAccount(userId: string, id: unknown): Acc | null {
  const a = typeof id === 'string' ? db.accounts.get(id) : undefined
  return a && a.userId === userId ? a : null
}

export const userById = (id: string) =>
  [...db.users.values()].find((u) => u.user.id === id)?.user ?? null

export { stepUpGate } from '../handlers'

export function available(acc: Acc): Decimal {
  const holds = db.v2.holds
    .filter((h) => h.accountId === acc.id && h.status === 'ACTIVE')
    .reduce((s, h) => s.plus(h.amount), new Decimal(0))
  return new Decimal(acc.balance).minus(holds)
}

/** Debit one account and credit another in one "journal entry" (mock ledger). */
export function move(
  from: Acc,
  to: Acc | null,
  amount: Decimal,
  type: TransactionDto['type'],
  fromDesc: string,
  toDesc: string,
  fromCounterparty: string | null = null,
  toCounterparty: string | null = null,
): TransactionDto {
  const entry = crypto.randomUUID()
  const tx = postLine(from, 'DEBIT', amount, type, fromDesc, fromCounterparty, entry)
  if (to) postLine(to, 'CREDIT', amount, type, toDesc, toCounterparty, entry)
  return tx
}

export const masked = (n: string) => last4(n)

export const todayIst = () =>
  new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Kolkata' }).format(new Date())

export function addDays(isoDate: string, days: number): string {
  const d = new Date(`${isoDate}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

export function dateRange(url: URL, defaultDays = 30): { from: string; to: string } | null {
  const to = url.searchParams.get('to') ?? todayIst()
  const from = url.searchParams.get('from') ?? addDays(to, -(defaultDays - 1))
  if (!/^\d{4}-\d{2}-\d{2}$/.test(from) || !/^\d{4}-\d{2}-\d{2}$/.test(to) || from > to) return null
  return { from, to }
}

export const daysBetween = (from: string, to: string) =>
  Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1

export const money = (d: Decimal | string) => new Decimal(d).toFixed(4)
export const pct = (d: Decimal) => d.toDecimalPlaces(2).toFixed(2)
