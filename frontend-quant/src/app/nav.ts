import {
  ArrowLeftRight,
  Bell,
  BriefcaseBusiness,
  CandlestickChart,
  Gauge,
  HandCoins,
  Inbox,
  LayoutDashboard,
  ReceiptText,
  Scale,
  ScrollText,
  UserRound,
  Users,
  Wallet,
  type LucideIcon,
} from 'lucide-react'
import type { Role } from '@/api/types'
import { useAuthStore } from '@/features/auth/store'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Shown in the mobile bottom tab bar (max 5). */
  mobile?: boolean
  /** Match only the exact path (for index routes like /admin). */
  end?: boolean
}

export const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, mobile: true },
  { to: '/accounts', label: 'Accounts', icon: Wallet, mobile: true },
  { to: '/transfer', label: 'Transfer', icon: ArrowLeftRight, mobile: true },
  { to: '/markets', label: 'Markets', icon: CandlestickChart, mobile: true },
  { to: '/portfolio', label: 'Portfolio', icon: BriefcaseBusiness },
  { to: '/orders', label: 'Orders', icon: ReceiptText },
  { to: '/loans', label: 'Loans', icon: HandCoins, mobile: true },
]

/** PRD §9 Admin: overview stats, users, loan queue, audit log, reconcile. */
export const ADMIN_NAV: NavItem[] = [
  { to: '/admin', label: 'Overview', icon: Gauge, mobile: true, end: true },
  { to: '/admin/users', label: 'Users', icon: Users, mobile: true },
  { to: '/admin/loans', label: 'Loan queue', icon: Inbox, mobile: true },
  { to: '/admin/audit', label: 'Audit log', icon: ScrollText, mobile: true },
  { to: '/admin/reconcile', label: 'Reconcile', icon: Scale, mobile: true },
]

export const SECONDARY_NAV: NavItem[] = [
  { to: '/notifications', label: 'Notifications', icon: Bell },
  { to: '/profile', label: 'Profile', icon: UserRound },
]

/** Where each role lands after sign-in. */
export const homeFor = (role: Role | undefined) => (role === 'ADMIN' ? '/admin' : '/dashboard')

/** The main nav for the signed-in role. UX only; the server enforces every permission. */
export function useMainNav(): NavItem[] {
  const role = useAuthStore((s) => s.user?.role)
  return role === 'ADMIN' ? ADMIN_NAV : NAV
}
