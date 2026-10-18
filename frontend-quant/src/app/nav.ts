import {
  ArrowLeftRight,
  Bell,
  Bot,
  BriefcaseBusiness,
  CandlestickChart,
  CreditCard,
  FlaskConical,
  Gauge,
  HandCoins,
  Inbox,
  LayoutDashboard,
  LineChart,
  PiggyBank,
  ReceiptText,
  Scale,
  ScrollText,
  Settings,
  ShieldAlert,
  ShieldCheck,
  Sparkles,
  ToggleRight,
  Users,
  Wallet,
  Workflow,
  type LucideIcon,
} from 'lucide-react'
import { useFlags } from '@/api/flags'
import type { FeatureFlag, Role } from '@/api/types'
import { useAuthStore } from '@/features/auth/store'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Shown in the mobile dock (max 4; the centre slot is the action button). */
  mobile?: boolean
  /** Match only the exact path (for index routes like /admin). */
  end?: boolean
  /** Hidden when this feature flag is off (UX only; the server enforces it). */
  flag?: FeatureFlag
  /** Short hint shown in the command palette. */
  hint?: string
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

/** Customer navigation, grouped (DESIGN.md v2 §4 "Shell"). */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: 'Overview',
    items: [
      { to: '/dashboard', label: 'Home', icon: LayoutDashboard, mobile: true },
      { to: '/insights', label: 'Insights', icon: LineChart, hint: 'Spending, cash flow, net worth' },
    ],
  },
  {
    label: 'Money',
    items: [
      { to: '/accounts', label: 'Accounts', icon: Wallet },
      { to: '/pay', label: 'Pay', icon: ArrowLeftRight, mobile: true, hint: 'Send, request, bills, split' },
      { to: '/savings', label: 'Savings', icon: PiggyBank, hint: 'Goals, round-ups, fixed deposits' },
      { to: '/cards', label: 'Cards', icon: CreditCard, flag: 'cards' },
    ],
  },
  {
    label: 'Invest',
    items: [
      { to: '/markets', label: 'Markets', icon: CandlestickChart, mobile: true },
      { to: '/portfolio', label: 'Portfolio', icon: BriefcaseBusiness },
      { to: '/orders', label: 'Orders', icon: ReceiptText },
      { to: '/lab', label: 'Strategy lab', icon: FlaskConical, hint: 'Backtest a strategy' },
    ],
  },
  {
    label: 'Credit',
    items: [{ to: '/loans', label: 'Loans', icon: HandCoins }],
  },
  {
    label: 'Automate',
    items: [
      { to: '/autopilot', label: 'Autopilot', icon: Workflow, flag: 'autopilot', hint: 'Money rules in plain English' },
      { to: '/copilot', label: 'Copilot', icon: Sparkles, flag: 'ai', mobile: true, hint: 'Ask about your money' },
    ],
  },
  {
    label: 'Trust',
    items: [{ to: '/proof', label: 'Proof of Balance', icon: ShieldCheck, hint: 'Verify your balance' }],
  },
]

/** Admin navigation (PRD §9 + v2 back office). */
export const ADMIN_GROUPS: NavGroup[] = [
  {
    label: 'Business',
    items: [
      { to: '/admin', label: 'Overview', icon: Gauge, mobile: true, end: true },
      { to: '/admin/analytics', label: 'Analytics', icon: LineChart, mobile: true },
    ],
  },
  {
    label: 'Customers',
    items: [
      { to: '/admin/users', label: 'Users', icon: Users, mobile: true },
      { to: '/admin/loans', label: 'Loan queue', icon: Inbox },
      { to: '/admin/fraud', label: 'Fraud cases', icon: ShieldAlert, mobile: true },
    ],
  },
  {
    label: 'Platform',
    items: [
      { to: '/admin/ai', label: 'AI usage', icon: Bot, flag: 'ai' },
      { to: '/admin/flags', label: 'Feature flags', icon: ToggleRight },
      { to: '/admin/audit', label: 'Audit log', icon: ScrollText },
      { to: '/admin/reconcile', label: 'Ledger integrity', icon: Scale },
    ],
  },
]

export const SECONDARY_NAV: NavItem[] = [
  { to: '/notifications', label: 'Notifications', icon: Bell },
  { to: '/settings', label: 'Settings', icon: Settings },
]

/** Where each role lands after sign-in. */
export const homeFor = (role: Role | undefined) => (role === 'ADMIN' ? '/admin' : '/dashboard')

/** The grouped nav for the signed-in role, with switched-off features removed. */
export function useNavGroups(): NavGroup[] {
  const role = useAuthStore((s) => s.user?.role)
  const flags = useFlags()
  const groups = role === 'ADMIN' ? ADMIN_GROUPS : NAV_GROUPS
  return groups
    .map((g) => ({ ...g, items: g.items.filter((i) => !i.flag || flags[i.flag]) }))
    .filter((g) => g.items.length > 0)
}

/** Flat list (command palette, mobile dock). */
export function useMainNav(): NavItem[] {
  return useNavGroups().flatMap((g) => g.items)
}
