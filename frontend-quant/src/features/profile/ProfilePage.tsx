import type { ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { KeyRound, LogOut, Monitor, Moon, ShieldCheck, Sun } from 'lucide-react'
import { ToggleGroup } from 'radix-ui'
import { useState } from 'react'
import { authApi } from '@/api/endpoints'
import { PageHeader } from '@/components/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useLogout } from '@/features/auth/session'
import { useAuthStore } from '@/features/auth/store'
import { useTheme } from '@/lib/theme'
import { formatAbsolute } from '@/lib/time'
import { useMediaQuery } from '@/lib/useMediaQuery'

/** "+919876543210" → "+91 ••••• •3210" unless revealed. */
function maskPhone(phone: string) {
  const last4 = phone.slice(-4)
  const cc = phone.startsWith('+') ? phone.slice(0, phone.length - 10) : ''
  return `${cc ? `${cc} ` : ''}••••• •${last4}`
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 py-3.5 sm:flex-row sm:items-center sm:justify-between">
      <dt className="text-sm text-muted">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  )
}

/** `embedded`: rendered as the Profile tab of Settings (no page header). */
export function ProfilePage({ embedded = false }: { embedded?: boolean } = {}) {
  const cached = useAuthStore((s) => s.user)
  const me = useQuery({
    queryKey: ['users', 'me'],
    queryFn: authApi.me,
    initialData: cached ?? undefined,
  })
  const user = me.data
  const [showPhone, setShowPhone] = useState(false)
  const theme = useTheme((s) => s.theme)
  const setTheme = useTheme((s) => s.setTheme)
  const reducedMotion = useMediaQuery('(prefers-reduced-motion: reduce)')
  const logout = useLogout()

  return (
    <div className="max-w-3xl">
      {!embedded && (
        <PageHeader title="Profile" description="Your details, preferences and session." />
      )}
      <div className="space-y-4">
        <Card>
          <div className="flex items-center gap-4">
            <span className="grid size-14 place-items-center rounded-full bg-accent text-lg font-semibold text-accent-ink">
              {user?.fullName
                .split(/\s+/)
                .map((p) => p[0])
                .slice(0, 2)
                .join('')
                .toUpperCase()}
            </span>
            <div className="min-w-0">
              {user ? (
                <p className="truncate text-xl font-medium">{user.fullName}</p>
              ) : (
                <Skeleton className="h-6 w-40" />
              )}
              {user && (
                <Badge tone={user.role === 'ADMIN' ? 'accent' : 'neutral'} className="mt-1">
                  {user.role === 'ADMIN' ? 'Admin' : 'Customer'}
                </Badge>
              )}
            </div>
          </div>
          {user && (
            <dl className="mt-4 divide-y divide-border">
              <Row label="Email">{user.email}</Row>
              <Row label="Phone">
                <span className="num">{showPhone ? user.phone : maskPhone(user.phone)}</span>{' '}
                <Button
                  variant="link"
                  className="ml-1 text-xs text-muted"
                  onClick={() => setShowPhone((s) => !s)}
                >
                  {showPhone ? 'Hide' : 'Show'}
                </Button>
              </Row>
              <Row label="Member since">{formatAbsolute(user.createdAt)}</Row>
            </dl>
          )}
          <p className="mt-2 text-xs text-muted">
            To change these details, contact support. (Self-service editing isn’t in v1.)
          </p>
        </Card>

        <Card>
          <h2 className="font-medium">Preferences</h2>
          <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <span className="text-sm text-muted">Theme</span>
            <ToggleGroup.Root
              type="single"
              value={theme}
              onValueChange={(v) => v && setTheme(v as 'dark' | 'light')}
              aria-label="Theme"
              className="inline-flex gap-1 rounded-[var(--radius-control)] bg-surface-2 p-1"
            >
              <ToggleGroup.Item
                value="dark"
                className="inline-flex items-center gap-1.5 rounded-[8px] px-3 py-1.5 text-sm text-muted data-[state=on]:bg-surface data-[state=on]:text-text"
              >
                <Moon aria-hidden="true" className="size-4" /> Dark
              </ToggleGroup.Item>
              <ToggleGroup.Item
                value="light"
                className="inline-flex items-center gap-1.5 rounded-[8px] px-3 py-1.5 text-sm text-muted data-[state=on]:bg-surface data-[state=on]:text-text"
              >
                <Sun aria-hidden="true" className="size-4" /> Light
              </ToggleGroup.Item>
            </ToggleGroup.Root>
          </div>
          <p className="mt-4 flex items-start gap-2 text-sm text-muted">
            <Monitor aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
            {reducedMotion
              ? 'Reduced motion is on in your system settings, so animations are kept to simple fades.'
              : 'Animations follow your system’s reduced-motion setting.'}
          </p>
        </Card>

        <Card>
          <h2 className="font-medium">Security</h2>
          <ul className="mt-4 space-y-3 text-sm">
            <li className="flex items-start gap-2.5">
              <KeyRound aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent" />
              <span>
                Your session token is kept in memory only and expires every 15 minutes; it renews
                silently while you’re active.
              </span>
            </li>
            <li className="flex items-start gap-2.5">
              <ShieldCheck aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-accent" />
              <span>
                After 10 minutes of inactivity we ask if you’re still there, then sign you out.
              </span>
            </li>
          </ul>
          <Button variant="secondary" className="mt-5" onClick={() => void logout()}>
            <LogOut aria-hidden="true" /> Sign out
          </Button>
        </Card>
      </div>
    </div>
  )
}
