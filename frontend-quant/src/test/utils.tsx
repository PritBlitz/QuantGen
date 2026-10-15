import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { Toaster } from 'sonner'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/router'

/** Render the real route tree at `path`, against MSW handlers. */
export function renderApp(path: string) {
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <Toaster />
    </QueryClientProvider>,
  )
  return { ...utils, router, queryClient }
}
