import { create } from 'zustand'
import type { AuthResponse, UserDto } from '@/api/types'

export type SessionStatus = 'unknown' | 'authenticated' | 'anonymous'

interface AuthState {
  /** Access JWT. Held in memory only: never persisted to any browser storage. */
  accessToken: string | null
  /** Epoch ms when the access token expires (client clock). */
  expiresAt: number | null
  user: UserDto | null
  status: SessionStatus
  setSession: (auth: AuthResponse) => void
  clearSession: () => void
}

// Deliberately NOT wrapped in zustand's `persist` middleware (CLAUDE.md: token in memory).
export const useAuthStore = create<AuthState>()((set) => ({
  accessToken: null,
  expiresAt: null,
  user: null,
  status: 'unknown',
  setSession: ({ accessToken, expiresIn, user }) =>
    set({
      accessToken,
      expiresAt: Date.now() + expiresIn * 1000,
      user,
      status: 'authenticated',
    }),
  clearSession: () => set({ accessToken: null, expiresAt: null, user: null, status: 'anonymous' }),
}))
