import { Slider as S } from 'radix-ui'
import { cn } from '@/lib/utils'

export function Slider({
  value,
  onValueChange,
  min,
  max,
  step,
  label,
  valueText,
  className,
}: {
  value: number
  onValueChange: (v: number) => void
  min: number
  max: number
  step: number
  label: string
  /** Human value for screen readers, e.g. "₹2,00,000". */
  valueText: string
  className?: string
}) {
  return (
    <S.Root
      value={[value]}
      onValueChange={(v) => onValueChange(v[0] ?? min)}
      min={min}
      max={max}
      step={step}
      className={cn('relative flex h-6 w-full touch-none select-none items-center', className)}
    >
      <S.Track className="relative h-1.5 grow overflow-hidden rounded-full bg-surface-2">
        <S.Range className="absolute h-full bg-accent" />
      </S.Track>
      <S.Thumb
        aria-label={label}
        aria-valuetext={valueText}
        className="block size-5 rounded-full border-2 border-accent bg-bg shadow-sm transition-transform focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-accent/30 active:scale-110"
      />
    </S.Root>
  )
}
