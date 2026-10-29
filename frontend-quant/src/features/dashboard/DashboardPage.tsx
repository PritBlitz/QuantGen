import { motion, useReducedMotion } from 'motion/react'
import { useAuthStore } from '@/features/auth/store'
import { EASE_OUT_EXPO } from '@/lib/motion'
import { ActivityCard } from './components/ActivityCard'
import { AccountsCard } from './components/AccountsCard'
import { LoanCard } from './components/LoanCard'
import { NetWorthCard } from './components/NetWorthCard'
import { OnboardingCard } from './components/OnboardingCard'
import { PortfolioCard } from './components/PortfolioCard'
import { QuickActions } from './components/QuickActions'
import {
  AutopilotCard,
  CopilotStrip,
  ProofCard,
  SpendingCard,
  UpcomingCard,
} from './components/V2Cards'
import { useAccounts } from './hooks'

function greeting(hour: number) {
  if (hour >= 5 && hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

const today = new Intl.DateTimeFormat('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })

/** Home (DESIGN.md v2 §4): a bento of the things people check first, then the things they do. */
export function DashboardPage() {
  const user = useAuthStore((s) => s.user)
  const accounts = useAccounts()
  const reduce = useReducedMotion()
  const now = new Date()
  const first = user?.fullName.split(' ')[0]

  return (
    <div>
      <header className="mb-6 flex flex-wrap items-end justify-between gap-2">
        <motion.h1
          initial={reduce ? false : { opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.3, ease: EASE_OUT_EXPO }}
          className="font-display text-2xl sm:text-[1.75rem]"
        >
          {greeting(now.getHours())}, {first}
        </motion.h1>
        <p className="text-sm text-muted">{today.format(now)}</p>
      </header>

      <div className="grid grid-cols-1 gap-4 md:grid-cols-6 lg:grid-cols-12">
        <NetWorthCard className="md:col-span-6 lg:col-span-8" />
        <QuickActions className="md:col-span-6 lg:col-span-4" />
        <OnboardingCard className="md:col-span-6 lg:col-span-12" />
        <CopilotStrip className="md:col-span-6 lg:col-span-12" />
        <AccountsCard className="md:col-span-3 lg:col-span-4" />
        <SpendingCard className="md:col-span-3 lg:col-span-4" />
        <UpcomingCard className="md:col-span-6 lg:col-span-4" />
        <PortfolioCard className="md:col-span-6 lg:col-span-7" />
        <ActivityCard accounts={accounts.data} className="md:col-span-6 lg:col-span-5" />
        <LoanCard className="md:col-span-3 lg:col-span-4" />
        <AutopilotCard className="md:col-span-3 lg:col-span-4" />
        <ProofCard className="md:col-span-6 lg:col-span-4" />
      </div>
    </div>
  )
}
