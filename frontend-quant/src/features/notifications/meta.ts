import {
  ArrowDownLeft,
  BadgeCheck,
  CalendarClock,
  CircleAlert,
  CircleCheck,
  CircleX,
  LockKeyhole,
  LockKeyholeOpen,
  type LucideIcon,
} from 'lucide-react'
import type { NotificationType } from '@/api/types'

type Tone = 'gain' | 'loss' | 'warn' | 'accent'

/** Icon, colour and destination per F-NOT-1 type. Copy itself always comes from the server. */
export const NOTIFICATION_META: Record<
  NotificationType,
  { icon: LucideIcon; tone: Tone; to: string; label: string }
> = {
  MONEY_RECEIVED: { icon: ArrowDownLeft, tone: 'gain', to: '/accounts', label: 'Money' },
  LOAN_APPROVED: { icon: BadgeCheck, tone: 'gain', to: '/loans', label: 'Loans' },
  LOAN_REJECTED: { icon: CircleX, tone: 'loss', to: '/loans', label: 'Loans' },
  EMI_DUE: { icon: CalendarClock, tone: 'warn', to: '/loans', label: 'Loans' },
  EMI_PAID: { icon: CircleCheck, tone: 'gain', to: '/loans', label: 'Loans' },
  EMI_OVERDUE: { icon: CircleAlert, tone: 'loss', to: '/loans', label: 'Loans' },
  ACCOUNT_FROZEN: { icon: LockKeyhole, tone: 'warn', to: '/accounts', label: 'Security' },
  ACCOUNT_UNFROZEN: { icon: LockKeyholeOpen, tone: 'accent', to: '/accounts', label: 'Security' },
}

export const TONE_CLASS: Record<Tone, string> = {
  gain: 'bg-gain/15 text-gain',
  loss: 'bg-loss/15 text-loss',
  warn: 'bg-warn/15 text-warn',
  accent: 'bg-accent/15 text-text',
}
