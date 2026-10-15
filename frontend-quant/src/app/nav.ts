import {
  ArrowLeftRight,
  Bell,
  BriefcaseBusiness,
  CandlestickChart,
  HandCoins,
  LayoutDashboard,
  UserRound,
  Wallet,
  type LucideIcon,
} from 'lucide-react'

export interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  /** Shown in the mobile bottom tab bar (max 5). */
  mobile?: boolean
  /** Milestone in which the page ships (PRD §10); absent = built. */
  milestone?: 'M2' | 'M3' | 'M4' | 'M5'
}

export const NAV: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard, mobile: true },
  { to: '/accounts', label: 'Accounts', icon: Wallet, mobile: true, milestone: 'M2' },
  { to: '/transfer', label: 'Transfer', icon: ArrowLeftRight, mobile: true, milestone: 'M2' },
  { to: '/markets', label: 'Markets', icon: CandlestickChart, mobile: true, milestone: 'M3' },
  { to: '/portfolio', label: 'Portfolio', icon: BriefcaseBusiness, milestone: 'M3' },
  { to: '/loans', label: 'Loans', icon: HandCoins, mobile: true, milestone: 'M4' },
]

export const SECONDARY_NAV: NavItem[] = [
  { to: '/notifications', label: 'Notifications', icon: Bell, milestone: 'M5' },
  { to: '/profile', label: 'Profile', icon: UserRound, milestone: 'M5' },
]
