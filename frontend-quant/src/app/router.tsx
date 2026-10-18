import { createBrowserRouter, Navigate, type RouteObject } from 'react-router'
import { GuestOnly, RequireAdmin, RequireAuth, SessionSplash } from './guards'
import { NotFoundPage, RouteErrorPage } from './pages'
import { Root } from './Root'

// Route-level code splitting: the landing page never downloads the app shell, and vice versa,
// and customers never download the admin console.
const admin: RouteObject = {
  path: 'admin',
  Component: RequireAdmin,
  children: [
    {
      index: true,
      lazy: async () => ({
        Component: (await import('@/features/admin/AdminOverviewPage')).AdminOverviewPage,
      }),
    },
    {
      path: 'analytics',
      lazy: async () => ({
        Component: (await import('@/features/analytics/AdminAnalyticsPage')).AdminAnalyticsPage,
      }),
    },
    {
      path: 'users',
      lazy: async () => ({
        Component: (await import('@/features/admin/AdminUsersPage')).AdminUsersPage,
      }),
    },
    {
      path: 'users/:id',
      lazy: async () => ({
        Component: (await import('@/features/admin/AdminUserDetailPage')).AdminUserDetailPage,
      }),
    },
    {
      path: 'loans',
      lazy: async () => ({
        Component: (await import('@/features/admin/AdminLoansPage')).AdminLoansPage,
      }),
    },
    {
      path: 'audit',
      lazy: async () => ({
        Component: (await import('@/features/admin/AdminAuditPage')).AdminAuditPage,
      }),
    },
    {
      path: 'reconcile',
      lazy: async () => ({
        Component: (await import('@/features/admin/AdminReconcilePage')).AdminReconcilePage,
      }),
    },
  ],
}

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
                path: 'insights',
                lazy: async () => ({
                  Component: (await import('@/features/insights/InsightsPage')).InsightsPage,
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
                path: 'pay',
                lazy: async () => ({
                  Component: (await import('@/features/payments/PayHubPage')).PayHubPage,
                }),
              },
              {
                path: 'pay/requests',
                lazy: async () => ({
                  Component: (await import('@/features/payments/RequestsPage')).RequestsPage,
                }),
              },
              {
                path: 'pay/scheduled',
                lazy: async () => ({
                  Component: (await import('@/features/payments/ScheduledPage')).ScheduledPage,
                }),
              },
              {
                path: 'pay/bills',
                lazy: async () => ({
                  Component: (await import('@/features/payments/BillsPage')).BillsPage,
                }),
              },
              {
                path: 'pay/splits',
                lazy: async () => ({
                  Component: (await import('@/features/payments/SplitsPage')).SplitsPage,
                }),
              },
              {
                path: 'pay/splits/:id',
                lazy: async () => ({
                  Component: (await import('@/features/payments/SplitsPage')).SplitGroupPage,
                }),
              },
              {
                path: 'pay/qr',
                lazy: async () => ({
                  Component: (await import('@/features/payments/QrPage')).QrPage,
                }),
              },
              {
                path: 'savings',
                lazy: async () => ({
                  Component: (await import('@/features/savings/SavingsPage')).SavingsPage,
                }),
              },
              {
                path: 'copilot',
                lazy: async () => ({
                  Component: (await import('@/features/copilot/CopilotPage')).CopilotPage,
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
              {
                path: 'loans',
                lazy: async () => ({
                  Component: (await import('@/features/loans/LoansPage')).LoansPage,
                }),
              },
              {
                path: 'loans/:id',
                lazy: async () => ({
                  Component: (await import('@/features/loans/LoanDetailPage')).LoanDetailPage,
                }),
              },
              { path: 'profile', element: <Navigate to="/settings" replace /> },
              {
                path: 'settings/:tab?',
                lazy: async () => ({
                  Component: (await import('@/features/security/SettingsPage')).SettingsPage,
                }),
              },
              {
                path: 'notifications',
                lazy: async () => ({
                  Component: (await import('@/features/notifications/NotificationsPage'))
                    .NotificationsPage,
                }),
              },
              admin,
            ],
          },
        ],
      },
      { path: '*', Component: NotFoundPage },
    ],
  },
]

export const router = createBrowserRouter(routes)
