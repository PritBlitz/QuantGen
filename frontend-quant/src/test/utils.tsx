import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { Providers } from '@/app/Providers'
import { createQueryClient } from '@/app/queryClient'
import { routes } from '@/app/router'
import { useAuthStore } from '@/features/auth/store'
import { ADMIN_USER_ID, DEMO_USER_ID } from '@/mocks/db'

/** Render the real route tree at `path` with the real providers, against MSW handlers. */
export function renderApp(path: string) {
  const queryClient = createQueryClient()
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const utils = render(
    <Providers queryClient={queryClient}>
      <RouterProvider router={router} />
    </Providers>,
  )
  return { ...utils, router, queryClient }
}

/** Put a mock session in the in-memory auth store (what /auth/login would have done). */
export function signInAs(role: 'CUSTOMER' | 'ADMIN') {
  const user =
    role === 'ADMIN'
      ? {
          id: ADMIN_USER_ID,
          fullName: 'Operations Admin',
          email: 'admin@quantgen.dev',
          phone: '+919800000001',
        }
      : {
          id: DEMO_USER_ID,
          fullName: 'Aarav Sharma',
          email: 'demo@quantgen.dev',
          phone: '+919876543210',
        }
  useAuthStore.setState({
    accessToken: `mock.${user.id}.00000000-0000-4000-8000-000000000000`,
    expiresAt: Date.now() + 900_000,
    status: 'authenticated',
    user: { ...user, role, createdAt: '2026-07-02T09:12:00Z' },
  })
}
