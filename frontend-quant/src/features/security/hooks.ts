import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { authApi } from '@/api/endpoints'
import { securityApi, tokensApi } from '@/api/endpoints.v2'
import type { KycRequest, MoneyString, TokenScope } from '@/api/types'
import { useAuthStore } from '@/features/auth/store'
import { createPasskey } from '@/lib/webauthn'

export const securityKeys = {
  me: ['users', 'me'] as const,
  passkeys: ['security', 'passkeys'] as const,
  sessions: ['security', 'sessions'] as const,
  limits: ['security', 'limits'] as const,
  kyc: ['security', 'kyc'] as const,
  tokens: ['security', 'tokens'] as const,
}

/** /users/me, seeded from the session so the page renders instantly. */
export function useMe() {
  const cached = useAuthStore((s) => s.user)
  return useQuery({
    queryKey: securityKeys.me,
    queryFn: authApi.me,
    initialData: cached ?? undefined,
  })
}

export const usePasskeys = () =>
  useQuery({ queryKey: securityKeys.passkeys, queryFn: securityApi.passkeys })
export const useSessions = () =>
  useQuery({ queryKey: securityKeys.sessions, queryFn: securityApi.sessions })
export const useLimits = () =>
  useQuery({ queryKey: securityKeys.limits, queryFn: securityApi.limits })
export const useKyc = () => useQuery({ queryKey: securityKeys.kyc, queryFn: securityApi.kyc })
export const useTokens = () => useQuery({ queryKey: securityKeys.tokens, queryFn: tokensApi.list })

function useInvalidate(...keys: ReadonlyArray<readonly string[]>) {
  const qc = useQueryClient()
  return () => Promise.all(keys.map((k) => qc.invalidateQueries({ queryKey: k })))
}

/** Register a passkey: options → browser prompt → server. Resolves null if dismissed. */
export function useAddPasskey() {
  const invalidate = useInvalidate(securityKeys.passkeys, securityKeys.me)
  return useMutation({
    mutationFn: async (name: string) => {
      const options = await securityApi.passkeyRegisterOptions()
      const credential = await createPasskey(options)
      if (!credential) return null
      return securityApi.passkeyRegister(credential, name)
    },
    onSuccess: invalidate,
  })
}

export function useRemovePasskey() {
  const invalidate = useInvalidate(securityKeys.passkeys, securityKeys.me)
  return useMutation({
    mutationFn: (id: string) => securityApi.removePasskey(id),
    onSuccess: invalidate,
  })
}

export function useSetPin() {
  const invalidate = useInvalidate(securityKeys.me)
  return useMutation({
    mutationFn: ({ pin, password }: { pin: string; password?: string }) =>
      securityApi.setPin(pin, password),
    onSuccess: invalidate,
  })
}

export function useRevokeSession() {
  const invalidate = useInvalidate(securityKeys.sessions)
  return useMutation({
    mutationFn: (id: string | 'others') =>
      id === 'others' ? securityApi.revokeOtherSessions() : securityApi.revokeSession(id),
    onSuccess: invalidate,
  })
}

export function useSetLimits() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { perTransfer?: MoneyString; daily?: MoneyString }) =>
      securityApi.setLimits(body),
    onSuccess: (data) => qc.setQueryData(securityKeys.limits, data),
  })
}

export function useSetKyc() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: KycRequest) => securityApi.setKyc(body),
    onSuccess: (data) => {
      qc.setQueryData(securityKeys.kyc, data)
      void qc.invalidateQueries({ queryKey: securityKeys.me })
    },
  })
}

export function useCreateToken() {
  const invalidate = useInvalidate(securityKeys.tokens)
  return useMutation({
    mutationFn: (body: { name: string; scope: TokenScope; expiresInDays: number }) =>
      tokensApi.create(body),
    onSuccess: invalidate,
  })
}

export function useRevokeToken() {
  const invalidate = useInvalidate(securityKeys.tokens)
  return useMutation({ mutationFn: (id: string) => tokensApi.revoke(id), onSuccess: invalidate })
}
