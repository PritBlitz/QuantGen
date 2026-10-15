import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useAuthStore } from '@/features/auth/store'
import { server } from '@/mocks/node'
import { api, refreshSession } from './client'
import { ApiError } from './errors'

const user = {
  id: '7c9e6679-7425-40de-944b-e07fc1f90ae7',
  fullName: 'Aarav Sharma',
  email: 'demo@quantgen.dev',
  phone: '+919876543210',
  role: 'CUSTOMER' as const,
  createdAt: '2026-07-02T09:12:00Z',
}

describe('api client', () => {
  it('shares one in-flight refresh across concurrent 401s, then retries each once', async () => {
    let refreshCalls = 0
    const seen: string[] = []
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls++
        return HttpResponse.json({ accessToken: 'fresh', expiresIn: 900, user })
      }),
      http.get('/api/v1/accounts', ({ request }) => {
        const auth = request.headers.get('Authorization') ?? ''
        seen.push(auth)
        return auth === 'Bearer fresh'
          ? HttpResponse.json([])
          : HttpResponse.json({ code: 'UNAUTHORIZED', status: 401 }, { status: 401 })
      }),
    )
    useAuthStore.setState({ accessToken: 'stale', status: 'authenticated', user, expiresAt: 0 })

    const results = await Promise.all([
      api.get('/accounts'),
      api.get('/accounts'),
      api.get('/accounts'),
    ])

    expect(results.every((r) => r.status === 200)).toBe(true)
    expect(refreshCalls).toBe(1)
    expect(seen.filter((h) => h === 'Bearer stale')).toHaveLength(3)
    expect(seen.filter((h) => h === 'Bearer fresh')).toHaveLength(3)
    expect(useAuthStore.getState().accessToken).toBe('fresh')
  })

  it('logs out when refresh is rejected, and does not loop', async () => {
    let refreshCalls = 0
    server.use(
      http.post('/api/v1/auth/refresh', () => {
        refreshCalls++
        return HttpResponse.json({ code: 'UNAUTHORIZED', status: 401 }, { status: 401 })
      }),
      http.get('/api/v1/accounts', () =>
        HttpResponse.json({ code: 'UNAUTHORIZED', status: 401 }, { status: 401 }),
      ),
    )
    useAuthStore.setState({ accessToken: 'stale', status: 'authenticated', user, expiresAt: 0 })

    await expect(api.get('/accounts')).rejects.toBeInstanceOf(ApiError)
    expect(refreshCalls).toBe(1)
    expect(useAuthStore.getState()).toMatchObject({ accessToken: null, status: 'anonymous' })
  })

  it('never sends the bearer token to /auth endpoints', async () => {
    let authHeader: string | null = 'unset'
    server.use(
      http.post('/api/v1/auth/refresh', ({ request }) => {
        authHeader = request.headers.get('Authorization')
        return HttpResponse.json({ accessToken: 'x', expiresIn: 900, user })
      }),
    )
    useAuthStore.setState({ accessToken: 'secret', status: 'authenticated', user, expiresAt: 0 })
    await refreshSession()
    expect(authHeader).toBeNull()
  })

  it('maps the §7.1 error body, including Retry-After', async () => {
    server.use(
      http.get('/api/v1/accounts', () =>
        HttpResponse.json(
          {
            timestamp: '2026-10-16T10:00:00Z',
            status: 429,
            code: 'RATE_LIMITED',
            message: 'Too many requests',
            path: '/api/v1/accounts',
            fieldErrors: [],
            requestId: 'req-1',
          },
          { status: 429, headers: { 'Retry-After': '120' } },
        ),
      ),
    )
    const err = await api.get('/accounts').catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({
      code: 'RATE_LIMITED',
      status: 429,
      retryAfter: 120,
      requestId: 'req-1',
    })
  })
})
