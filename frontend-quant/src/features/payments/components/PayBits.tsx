import QRCode from 'qrcode'
import { useEffect, useRef } from 'react'
import { NavLink } from 'react-router'
import { cn } from '@/lib/utils'

const TABS = [
  { to: '/pay', label: 'Overview', end: true },
  { to: '/transfer', label: 'Send' },
  { to: '/pay/requests', label: 'Requests' },
  { to: '/pay/scheduled', label: 'Scheduled' },
  { to: '/pay/bills', label: 'Bills' },
  { to: '/pay/splits', label: 'Split' },
  { to: '/pay/qr', label: 'QR' },
]

/** Sub-navigation shared by every Pay page. */
export function PayTabs() {
  return (
    <nav aria-label="Payments" className="scroll-thin -mt-4 mb-8 flex gap-1 overflow-x-auto">
      {TABS.map((t) => (
        <NavLink
          key={t.to}
          to={t.to}
          end={t.end}
          className={({ isActive }) =>
            cn(
              'shrink-0 rounded-full px-4 py-1.5 text-sm transition-colors hairline',
              isActive ? 'bg-text text-bg' : 'text-muted hover:border-border-strong hover:text-text',
            )
          }
        >
          {t.label}
        </NavLink>
      ))}
    </nav>
  )
}

/** Renders a QR code to a canvas (no data: URLs, no network). Colours follow the theme. */
export function QrCanvas({ value, size = 220, className }: { value: string; size?: number; className?: string }) {
  const ref = useRef<HTMLCanvasElement>(null)
  useEffect(() => {
    const canvas = ref.current
    if (!canvas) return
    const css = getComputedStyle(document.documentElement)
    void QRCode.toCanvas(canvas, value, {
      width: size,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: css.getPropertyValue('--text').trim() || '#000000', light: '#00000000' },
    })
  }, [value, size])
  return <canvas ref={ref} width={size} height={size} className={className} aria-label="Payment QR code" role="img" />
}

/** Initials avatar used across payments. */
export function Avatar({ name, className }: { name: string; className?: string }) {
  const initials = name
    .replace(/@qg$/, '')
    .split(/[\s.]+/)
    .filter(Boolean)
    .map((w) => (w[0] ?? '').toUpperCase())
    .slice(0, 2)
    .join('')
  return (
    <span
      aria-hidden="true"
      className={cn(
        'grid size-9 shrink-0 place-items-center rounded-full bg-gradient-to-br from-violet/35 to-cyan/25 text-xs font-semibold hairline',
        className,
      )}
    >
      {initials || '·'}
    </span>
  )
}
