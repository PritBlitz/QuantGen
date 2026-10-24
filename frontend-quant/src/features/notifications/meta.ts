import {
  ArrowDownLeft,
  BadgeCheck,
  CalendarClock,
  CircleAlert,
  CircleCheck,
  CircleX,
  CreditCard,
  HandCoins,
  LockKeyhole,
  LockKeyholeOpen,
  MonitorSmartphone,
  PiggyBank,
  ShieldAlert,
  TrendingUp,
  Wallet,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import type { NotificationDto } from '@/api/types'

type Tone = 'gain' | 'loss' | 'warn' | 'accent' | 'violet'

/** Icon, colour and destination per F-NOT-1 type. Copy itself always comes from the server. */
type Meta = { icon: LucideIcon; tone: Tone; to: string; label: string }

/** Fallback for a type this build doesn't know yet (the server can add types). */
export const UNKNOWN_META: Meta = { icon: CircleAlert, tone: 'accent', to: '/notifications', label: 'Update' }

export const metaFor = (type: NotificationDto['type']): Meta => NOTIFICATION_META[type] ?? UNKNOWN_META

export const NOTIFICATION_META: Record<NotificationDto['type'], Meta> = {
  MONEY_RECEIVED: { icon: ArrowDownLeft, tone: 'gain', to: '/accounts', label: 'Money' },
  LOAN_APPROVED: { icon: BadgeCheck, tone: 'gain', to: '/loans', label: 'Loans' },
  LOAN_REJECTED: { icon: CircleX, tone: 'loss', to: '/loans', label: 'Loans' },
  EMI_DUE: { icon: CalendarClock, tone: 'warn', to: '/loans', label: 'Loans' },
  EMI_PAID: { icon: CircleCheck, tone: 'gain', to: '/loans', label: 'Loans' },
  EMI_OVERDUE: { icon: CircleAlert, tone: 'loss', to: '/loans', label: 'Loans' },
  ACCOUNT_FROZEN: { icon: LockKeyhole, tone: 'warn', to: '/accounts', label: 'Security' },
  ACCOUNT_UNFROZEN: { icon: LockKeyholeOpen, tone: 'accent', to: '/accounts', label: 'Security' },
  PAYMENT_REQUEST: { icon: Wallet, tone: 'accent', to: '/pay/requests', label: 'Payments' },
  SCHEDULED_FAILED: { icon: CircleAlert, tone: 'loss', to: '/pay/scheduled', label: 'Payments' },
  RULE_RUN: { icon: Workflow, tone: 'violet', to: '/autopilot', label: 'Autopilot' },
  RULE_PAUSED: { icon: Workflow, tone: 'warn', to: '/autopilot', label: 'Autopilot' },
  BUDGET_ALERT: { icon: PiggyBank, tone: 'warn', to: '/insights', label: 'Budgets' },
  NEW_DEVICE_LOGIN: { icon: MonitorSmartphone, tone: 'warn', to: '/settings/security', label: 'Security' },
  TRANSFER_UNDER_REVIEW: { icon: ShieldAlert, tone: 'warn', to: '/pay', label: 'Payments' },
  PRICE_ALERT: { icon: TrendingUp, tone: 'accent', to: '/markets', label: 'Markets' },
  FD_MATURED: { icon: HandCoins, tone: 'gain', to: '/savings', label: 'Savings' },
  CARD_DECLINED: { icon: CreditCard, tone: 'loss', to: '/cards', label: 'Cards' },
  CARD_DISPUTE_RESOLVED: { icon: CreditCard, tone: 'accent', to: '/cards', label: 'Cards' },
  LEDGER_INTEGRITY: { icon: ShieldAlert, tone: 'loss', to: '/admin/reconcile', label: 'Ledger' },
}

export const TONE_CLASS: Record<Tone, string> = {
  gain: 'bg-gain/15 text-gain',
  loss: 'bg-loss/15 text-loss',
  warn: 'bg-warn/15 text-warn',
  accent: 'bg-accent/15 text-text',
  violet: 'bg-violet/14 text-violet',
}
