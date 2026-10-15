import { Tooltip as T } from 'radix-ui'
import type { ReactNode } from 'react'

export const TooltipProvider = T.Provider

export function Tooltip({ content, children }: { content: ReactNode; children: ReactNode }) {
  return (
    <T.Root>
      <T.Trigger asChild>{children}</T.Trigger>
      <T.Portal>
        <T.Content
          sideOffset={6}
          className="z-50 rounded-md bg-surface-2 px-2.5 py-1.5 text-xs text-text hairline shadow-[var(--shadow-pop)]"
        >
          {content}
        </T.Content>
      </T.Portal>
    </T.Root>
  )
}
