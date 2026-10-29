import { cva, type VariantProps } from 'class-variance-authority'
import { Slot } from 'radix-ui'
import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { cn } from '@/lib/utils'

const buttonVariants = cva(
  [
    'relative inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-[var(--radius-control)]',
    'text-sm font-medium select-none',
    'transition-[transform,background-color,border-color,color,opacity,box-shadow] duration-[var(--dur-micro)] ease-[var(--ease-out-expo)]',
    'disabled:pointer-events-none disabled:opacity-50',
    '[&_svg]:size-4 [&_svg]:shrink-0',
  ],
  {
    variants: {
      variant: {
        primary: [
          'bg-accent text-accent-ink',
          'hover:bg-[color-mix(in_oklab,var(--accent)_88%,black)]',
        ],
        /** AI actions (Copilot, Autopilot compile). */
        violet: [
          'bg-violet text-violet-ink',
          'hover:bg-[color-mix(in_oklab,var(--violet)_88%,black)]',
        ],
        secondary: 'bg-surface text-text hairline hover:border-border-strong hover:bg-surface-2',
        ghost: 'text-text hover:bg-surface-2',
        outline: 'hairline text-text hover:border-border-strong hover:bg-surface-2',
        glass: 'glass text-text hover:border-border-strong',
        destructive: 'bg-loss text-bg hover:opacity-90',
        link: 'text-text underline-offset-4 hover:underline px-0 h-auto',
      },
      size: {
        sm: 'h-8 px-3 text-[0.8125rem]',
        md: 'h-10 px-4',
        lg: 'h-12 px-6 text-base',
        xl: 'h-12 px-7 text-base',
        icon: 'size-10',
        'icon-sm': 'size-8',
      },
    },
    defaultVariants: { variant: 'primary', size: 'md' },
  },
)

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>, VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, type, ...props }, ref) => {
    const Comp = asChild ? Slot.Root : 'button'
    return (
      <Comp
        ref={ref}
        // Default to type="button" so a stray button never submits a form.
        type={asChild ? undefined : (type ?? 'button')}
        className={cn(buttonVariants({ variant, size }), className)}
        {...props}
      />
    )
  },
)
Button.displayName = 'Button'
