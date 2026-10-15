import { cn } from '@/lib/utils'

export function LogoMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 32 32" aria-hidden="true" className={cn('size-7', className)}>
      <rect width="32" height="32" rx="8" fill="var(--text)" />
      <circle cx="15" cy="15" r="7.5" fill="none" stroke="var(--accent)" strokeWidth="3" />
      <path d="M19.5 19.5 25 25" stroke="var(--accent)" strokeWidth="3" strokeLinecap="round" />
    </svg>
  )
}

export function Logo({ className }: { className?: string }) {
  return (
    <span className={cn('inline-flex items-center gap-2.5', className)}>
      <LogoMark />
      <span className="text-[1.0625rem] font-semibold tracking-tight">QuantGen</span>
    </span>
  )
}
