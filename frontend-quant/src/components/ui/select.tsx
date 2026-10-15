import { Check, ChevronDown } from 'lucide-react'
import { Select as S } from 'radix-ui'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface SelectOption {
  value: string
  label: ReactNode
  /** Plain text for typeahead and the trigger's accessible name. */
  text?: string
  disabled?: boolean
}

export function Select({
  id,
  value,
  onValueChange,
  options,
  placeholder,
  className,
  invalid,
  'aria-describedby': describedBy,
}: {
  id?: string
  value: string | undefined
  onValueChange: (value: string) => void
  options: SelectOption[]
  placeholder?: string
  className?: string
  invalid?: boolean
  'aria-describedby'?: string
}) {
  return (
    <S.Root value={value ?? ''} onValueChange={onValueChange}>
      <S.Trigger
        id={id}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        className={cn(
          'flex h-11 w-full items-center justify-between gap-2 rounded-[var(--radius-control)] bg-surface-2 px-3.5 text-left text-[0.9375rem]',
          'hairline hover:border-muted/40 focus-visible:border-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/30',
          'aria-[invalid=true]:border-loss data-[placeholder]:text-muted',
          className,
        )}
      >
        <span className="min-w-0 flex-1 truncate">
          <S.Value placeholder={placeholder} />
        </span>
        <S.Icon>
          <ChevronDown aria-hidden="true" className="size-4 text-muted" />
        </S.Icon>
      </S.Trigger>
      <S.Portal>
        <S.Content
          position="popper"
          sideOffset={6}
          className="z-50 max-h-[min(22rem,var(--radix-select-content-available-height))] w-[var(--radix-select-trigger-width)] overflow-hidden rounded-[var(--radius-card)] bg-surface p-1 hairline shadow-[var(--shadow-pop)]"
        >
          <S.Viewport>
            {options.map((o) => (
              <S.Item
                key={o.value}
                value={o.value}
                disabled={o.disabled}
                textValue={o.text}
                className="relative flex cursor-pointer select-none items-center gap-2 rounded-[var(--radius-control)] py-2.5 pl-3 pr-8 text-sm outline-none data-[disabled]:pointer-events-none data-[highlighted]:bg-surface-2 data-[disabled]:opacity-50"
              >
                <S.ItemText>{o.label}</S.ItemText>
                <S.ItemIndicator className="absolute right-2.5">
                  <Check aria-hidden="true" className="size-4 text-accent" />
                </S.ItemIndicator>
              </S.Item>
            ))}
          </S.Viewport>
        </S.Content>
      </S.Portal>
    </S.Root>
  )
}
