/**
 * Audit actions written by the backend (auth and payments since M1/M2; account and loan
 * decisions at M5). Unknown actions still render, just with their raw name.
 */
export const AUDIT_ACTIONS: Record<
  string,
  { label: string; tone: 'neutral' | 'gain' | 'loss' | 'warn' | 'accent' }
> = {
  USER_REGISTERED: { label: 'User registered', tone: 'neutral' },
  LOGIN_SUCCESS: { label: 'Login', tone: 'neutral' },
  LOGIN_FAILED: { label: 'Login failed', tone: 'warn' },
  LOGIN_LOCKED: { label: 'Login locked', tone: 'loss' },
  LOGIN_BLOCKED: { label: 'Login blocked', tone: 'loss' },
  LOGOUT: { label: 'Logout', tone: 'neutral' },
  REFRESH_TOKEN_REUSE: { label: 'Token reuse', tone: 'loss' },
  LARGE_TRANSFER: { label: 'Large transfer', tone: 'accent' },
  ACCOUNT_FROZEN: { label: 'Account frozen', tone: 'warn' },
  ACCOUNT_UNFROZEN: { label: 'Account unfrozen', tone: 'gain' },
  LOAN_APPROVED: { label: 'Loan approved', tone: 'gain' },
  LOAN_REJECTED: { label: 'Loan rejected', tone: 'loss' },
}

export const auditAction = (action: string) =>
  AUDIT_ACTIONS[action] ?? { label: action, tone: 'neutral' as const }
