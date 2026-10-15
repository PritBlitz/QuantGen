import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/mocks/node'
import { renderApp } from '@/test/utils'
import { useAuthStore } from './store'

describe('auth flows', () => {
  it('redirects anonymous users from the dashboard to login', async () => {
    const { router } = renderApp('/dashboard')
    expect(await screen.findByRole('heading', { name: 'Welcome back' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('signs in and lands on the dashboard with server data', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/login')
    await user.type(await screen.findByLabelText('Email'), 'demo@quantgen.dev')
    await user.type(screen.getByLabelText('Password'), 'Demo@1234')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'))
    expect(await screen.findByRole('heading', { name: /aarav/i })).toBeInTheDocument()
    // Account balance rendered from the mock, formatted from the decimal string.
    expect(await screen.findByText('₹1,84,250.75')).toBeInTheDocument()
    expect(useAuthStore.getState().accessToken).toMatch(/^mock\./)
  })

  it('shows one generic message for bad credentials', async () => {
    const user = userEvent.setup()
    renderApp('/login')
    await user.type(await screen.findByLabelText('Email'), 'demo@quantgen.dev')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: /sign in/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/don’t match/)
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('shows the lockout message on 429', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json(
          {
            timestamp: '',
            status: 429,
            code: 'RATE_LIMITED',
            message: 'locked',
            path: '',
            fieldErrors: [],
            requestId: 'r',
          },
          { status: 429, headers: { 'Retry-After': '840' } },
        ),
      ),
    )
    const user = userEvent.setup()
    renderApp('/login')
    await user.type(await screen.findByLabelText('Email'), 'demo@quantgen.dev')
    await user.type(screen.getByLabelText('Password'), 'x')
    await user.click(screen.getByRole('button', { name: /sign in/i }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 14 minutes')
  })

  it('validates the register form client-side before calling the API', async () => {
    let called = false
    server.use(
      http.post('/api/v1/auth/register', () => {
        called = true
        return HttpResponse.json({}, { status: 500 })
      }),
    )
    const user = userEvent.setup()
    renderApp('/register')
    await user.type(await screen.findByLabelText('Full name'), 'A')
    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.type(screen.getByLabelText('Phone'), '123')
    await user.type(screen.getByLabelText('Password'), 'weakpass')
    await user.type(screen.getByLabelText('Confirm password'), 'different')
    await user.click(screen.getByRole('button', { name: /create account/i }))

    expect(await screen.findByText('Enter your full name')).toBeInTheDocument()
    expect(screen.getByText('Enter a valid email address')).toBeInTheDocument()
    expect(screen.getByText(/valid phone number/)).toBeInTheDocument()
    expect(screen.getByText(/doesn’t meet every rule/)).toBeInTheDocument()
    expect(screen.getByText('Passwords don’t match')).toBeInTheDocument()
    expect(called).toBe(false)
  })

  it('maps a duplicate-email 409 onto the email field', async () => {
    const user = userEvent.setup()
    renderApp('/register')
    await user.type(await screen.findByLabelText('Full name'), 'Aarav Sharma')
    await user.type(screen.getByLabelText('Email'), 'demo@quantgen.dev')
    await user.type(screen.getByLabelText('Phone'), '+91 98765 43210')
    await user.type(screen.getByLabelText('Password'), 'Str0ng!Pass')
    await user.type(screen.getByLabelText('Confirm password'), 'Str0ng!Pass')
    await user.click(screen.getByRole('button', { name: /create account/i }))
    expect(await screen.findByText(/already exists/)).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true')
  })

  it('registers, signs in and opens the dashboard', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/register')
    await user.type(await screen.findByLabelText('Full name'), 'Priya Nair')
    await user.type(screen.getByLabelText('Email'), 'priya@example.com')
    await user.type(screen.getByLabelText('Phone'), '+919812345678')
    await user.type(screen.getByLabelText('Password'), 'Str0ng!Pass')
    await user.type(screen.getByLabelText('Confirm password'), 'Str0ng!Pass')
    await user.click(screen.getByRole('button', { name: /create account/i }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/dashboard'))
    expect(await screen.findByRole('heading', { name: /priya/i })).toBeInTheDocument()
  })
})
