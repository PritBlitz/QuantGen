import { useAuthStore } from '@/features/auth/store'
import { ActivityCard } from './components/ActivityCard'
import { AccountsCard } from './components/AccountsCard'
import { LoanCard } from './components/LoanCard'
import { NetWorthCard } from './components/NetWorthCard'
import { PortfolioCard } from './components/PortfolioCard'
import { QuickActions } from './components/QuickActions'
import { useAccounts } from './hooks'

function greeting(hour: number) {
  if (hour < 12) return 'Good morning'
  if (hour < 17) return 'Good afternoon'
  return 'Good evening'
}

const today = new Intl.DateTimeFormat('en-IN', { weekday: 'long', day: 'numeric', month: 'long' })

export function DashboardPage() {
  const user = useAuthStore((s) => s.user)
  const accounts = useAccounts()
  const now = new Date()

  return (
    <div>
      <header className="mb-8">
        <p className="text-sm text-muted">{today.format(now)}</p>
        <h1 className="mt-1 font-display text-4xl sm:text-5xl">
          {greeting(now.getHours())}, {user?.fullName.split(' ')[0]}
        </h1>
      </header>

      {/* Bento grid (DESIGN.md §4). */}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-6 lg:grid-cols-12">
        <NetWorthCard className="md:col-span-6 lg:col-span-8" />
        <QuickActions className="md:col-span-6 lg:col-span-4" />
        <AccountsCard className="md:col-span-3 lg:col-span-5" />
        <PortfolioCard className="md:col-span-3 lg:col-span-7" />
        <ActivityCard accounts={accounts.data} className="md:col-span-6 lg:col-span-7" />
        <LoanCard className="md:col-span-6 lg:col-span-5" />
      </div>
    </div>
  )
}
