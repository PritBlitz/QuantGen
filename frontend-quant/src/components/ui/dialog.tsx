import { Dialog as DialogPrimitive } from 'radix-ui'
import { X } from 'lucide-react'
import type { ComponentProps, ReactNode } from 'react'
import { cn } from '@/lib/utils'

export const Dialog = DialogPrimitive.Root
export const DialogTrigger = DialogPrimitive.Trigger
export const DialogClose = DialogPrimitive.Close

const overlay =
  'fixed inset-0 z-50 bg-black/55 backdrop-blur-[3px] data-[state=open]:animate-[fade-in_200ms_var(--ease-out-expo)]'

export function DialogContent({
  className,
  children,
  title,
  description,
  ...props
}: ComponentProps<typeof DialogPrimitive.Content> & { title: ReactNode; description?: ReactNode }) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className={overlay} />
      <DialogPrimitive.Content
        className={cn(
          'fixed left-1/2 top-1/2 z-50 max-h-[calc(100dvh-2rem)] w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 overflow-y-auto scroll-thin',
          'edge-light rounded-[var(--radius-sheet)] bg-surface-solid p-6 hairline shadow-[var(--shadow-pop)]',
          'data-[state=open]:animate-[dialog-in_260ms_var(--ease-out-expo)]',
          'focus-visible:outline-none',
          className,
        )}
        {...props}
      >
        <DialogPrimitive.Title className="pr-8 text-lg font-semibold tracking-tight">
          {title}
        </DialogPrimitive.Title>
        {description ? (
          <DialogPrimitive.Description className="mt-1.5 text-sm text-muted">
            {description}
          </DialogPrimitive.Description>
        ) : (
          <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>
        )}
        <DialogPrimitive.Close
          aria-label="Close"
          className="absolute right-4 top-4 grid size-8 place-items-center rounded-full text-muted hover:bg-surface-2 hover:text-text"
        >
          <X aria-hidden="true" className="size-4" />
        </DialogPrimitive.Close>
        <div className="mt-5">{children}</div>
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  )
}

/**
 * A panel that slides in from the right (desktop) and up from the bottom (mobile). Used for the
 * Copilot, detail drawers and the mobile "more" menu. Built on Radix Dialog for focus trapping.
 */
export function SheetContent({
  className,
  children,
  title,
  description,
  hideTitle,
  side = 'right',
  ...props
}: ComponentProps<typeof DialogPrimitive.Content> & {
  title: ReactNode
  description?: ReactNode
  hideTitle?: boolean
  side?: 'right' | 'bottom'
}) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className={overlay} />
      <DialogPrimitive.Content
        className={cn(
          'fixed z-50 flex flex-col bg-surface-solid hairline shadow-[var(--shadow-pop)] focus-visible:outline-none',
          side === 'right'
            ? 'inset-y-2 right-2 w-[calc(100vw-1rem)] max-w-[460px] rounded-[var(--radius-sheet)] data-[state=open]:animate-[sheet-right_320ms_var(--ease-out-expo)]'
            : 'inset-x-0 bottom-0 max-h-[88dvh] rounded-t-[var(--radius-sheet)] pb-[env(safe-area-inset-bottom)] data-[state=open]:animate-[sheet-up_320ms_var(--ease-out-expo)]',
          className,
        )}
        {...props}
      >
        <div className={cn('flex items-start gap-3 px-5 pt-5', hideTitle && 'sr-only')}>
          <div className="min-w-0 flex-1">
            <DialogPrimitive.Title className="text-base font-semibold tracking-tight">
              {title}
            </DialogPrimitive.Title>
            {description ? (
              <DialogPrimitive.Description className="mt-1 text-sm text-muted">
                {description}
              </DialogPrimitive.Description>
            ) : (
              <DialogPrimitive.Description className="sr-only">{title}</DialogPrimitive.Description>
            )}
          </div>
        </div>
        <DialogPrimitive.Close
          aria-label="Close"
          className="absolute right-4 top-4 z-10 grid size-8 place-items-center rounded-full text-muted hover:bg-surface-2 hover:text-text"
        >
          <X aria-hidden="true" className="size-4" />
        </DialogPrimitive.Close>
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  )
}
