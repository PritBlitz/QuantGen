import { QueryClientProvider } from '@tanstack/react-query'
import { MotionConfig } from 'motion/react'
import { useState } from 'react'
import { RouterProvider } from 'react-router'
import { Toaster } from 'sonner'
import { TooltipProvider } from '@/components/ui/tooltip'
import { useTheme } from '@/lib/theme'
import { createQueryClient } from './queryClient'
import { router } from './router'

export function App() {
  const [queryClient] = useState(createQueryClient)
  const theme = useTheme((s) => s.theme)
  return (
    <QueryClientProvider client={queryClient}>
      {/* reducedMotion="user": motion respects prefers-reduced-motion everywhere. */}
      <MotionConfig reducedMotion="user">
        <TooltipProvider delayDuration={300}>
          <RouterProvider router={router} />
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
