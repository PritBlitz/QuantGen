import { createBrowserRouter, type RouteObject } from 'react-router'
import { GuestOnly, RequireAuth, SessionSplash } from './guards'
import { NAV, SECONDARY_NAV } from './nav'
import { ComingSoonPage, NotFoundPage, RouteErrorPage } from './pages'
import { Root } from './Root'

// Route-level code splitting: the landing page never downloads the app shell, and vice versa.
const comingSoon: RouteObject[] = [...NAV, ...SECONDARY_NAV]
  .filter((n) => n.milestone)
  .map((n) => ({ path: n.to.slice(1), Component: ComingSoonPage }))

export const routes: RouteObject[] = [
  {
    Component: Root,
    errorElement: <RouteErrorPage />,
    HydrateFallback: SessionSplash,
    children: [
      {
        lazy: async () => ({ Component: (await import('./layouts/LandingLayout')).LandingLayout }),
        children: [
          {
            index: true,
            lazy: async () => ({
              Component: (await import('@/features/landing/LandingPage')).LandingPage,
            }),
          },
        ],
      },
      {
        Component: GuestOnly,
        children: [
          {
            lazy: async () => ({ Component: (await import('./layouts/AuthLayout')).AuthLayout }),
            children: [
              {
                path: 'login',
                lazy: async () => ({
                  Component: (await import('@/features/auth/pages/LoginPage')).LoginPage,
                }),
              },
              {
                path: 'register',
                lazy: async () => ({
                  Component: (await import('@/features/auth/pages/RegisterPage')).RegisterPage,
                }),
              },
            ],
          },
        ],
      },
      {
        Component: RequireAuth,
        children: [
          {
            lazy: async () => ({ Component: (await import('./layouts/AppShell')).AppShell }),
            children: [
              {
                path: 'dashboard',
                lazy: async () => ({
                  Component: (await import('@/features/dashboard/DashboardPage')).DashboardPage,
                }),
              },
              {
                path: 'accounts',
                lazy: async () => ({
                  Component: (await import('@/features/accounts/AccountsPage')).AccountsPage,
                }),
              },
              {
                path: 'accounts/:id',
                lazy: async () => ({
                  Component: (await import('@/features/accounts/AccountDetailPage'))
                    .AccountDetailPage,
                }),
              },
              {
                path: 'transfer',
                lazy: async () => ({
                  Component: (await import('@/features/transfers/TransferPage')).TransferPage,
                }),
              },
              {
                path: 'markets',
                lazy: async () => ({
                  Component: (await import('@/features/markets/MarketsPage')).MarketsPage,
                }),
              },
              {
                path: 'markets/:symbol',
                lazy: async () => ({
                  Component: (await import('@/features/markets/InstrumentPage')).InstrumentPage,
                }),
              },
              {
                path: 'portfolio',
                lazy: async () => ({
                  Component: (await import('@/features/portfolio/PortfolioPage')).PortfolioPage,
                }),
              },
              {
                path: 'orders',
                lazy: async () => ({
                  Component: (await import('@/features/portfolio/OrdersPage')).OrdersPage,
                }),
              },
              ...comingSoon,
            ],
          },
        ],
      },
      { path: '*', Component: NotFoundPage },
    ],
  },
]

export const router = createBrowserRouter(routes)
