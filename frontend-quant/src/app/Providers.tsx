import { QueryClientProvider, type QueryClient } from '@tanstack/react-query'
import { MotionConfig } from 'motion/react'
import type { ReactNode } from 'react'
import { Toaster } from 'sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { useTheme } from '@/lib/theme'

/** Everything around the router. Shared by the app and the test harness. */
export function Providers({
  queryClient,
  children,
}: {
  queryClient: QueryClient
  children: ReactNode
}) {
  const theme = useTheme((s) => s.theme)
  return (
    <QueryClientProvider client={queryClient}>
      {/* reducedMotion="user": motion respects prefers-reduced-motion everywhere. */}
      <MotionConfig reducedMotion="user">
        <TooltipProvider delayDuration={300}>
          {children}
          <Toaster
            theme={theme}
            position="bottom-right"
            offset={{ bottom: 88, right: 16 }}
            toastOptions={{
              className:
                '!bg-surface !text-text !border-border !rounded-[var(--radius-card)] !font-sans',
            }}
          />
        </TooltipProvider>
      </MotionConfig>
    </QueryClientProvider>
  )
}
