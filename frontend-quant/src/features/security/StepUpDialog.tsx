import { Fingerprint, KeyRound, ShieldCheck } from 'lucide-react'
import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import { securityApi } from '@/api/endpoints.v2'
import { errorMessage, toApiError } from '@/api/errors'
import { setStepUpHandler } from '@/api/stepUp'
import type { StepUpAction, StepUpChallenge, StepUpMethod } from '@/api/types'
import { PinInput } from '@/components/PinInput'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent } from '@/components/ui/dialog'
import { getPasskeyAssertion, webauthnSupported } from '@/lib/webauthn'

const ACTION_COPY: Record<StepUpAction, string> = {
  TRANSFER: 'Confirm this payment',
  INTENT_APPROVE: 'Confirm the assistant’s suggestion',
  RULE_ACTIVATE: 'Confirm your Autopilot rule',
  CARD_REVEAL: 'Reveal your card details',
  SECURITY_SETTINGS: 'Confirm a security change',
  FD_CLOSE: 'Confirm closing your deposit',
  TOKEN_CREATE: 'Confirm creating an access token',
}

/** "That PIN didn’t match (3 attempts left)." when the server says how many tries remain. */
function pinError(message: string | undefined) {
  const left = message?.match(/\d+ attempts? left/)?.[0]
  return left ? `That PIN didn’t match (${left}).` : 'That PIN didn’t match. Try again.'
}

interface Pending {
  challenge: StepUpChallenge
  resolve: (token: string | null) => void
}

/**
 * Step-up verification (PRD F-SEC-2, ARCHITECTURE §12.5). Mounted once in the app shell. The API
 * client calls the registered handler on `401 STEP_UP_REQUIRED`; this dialog collects a passkey
 * assertion or the 6-digit PIN, exchanges it for a short-lived token and hands it back. The
 * client then retries the original request with the same Idempotency-Key.
 */
export function StepUpDialog() {
  const navigate = useNavigate()
  const [pending, setPending] = useState<Pending | null>(null)
  const pendingRef = useRef<Pending | null>(null)
  const [method, setMethod] = useState<StepUpMethod>('PASSKEY')
  const [pin, setPin] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [passkeyOptions, setPasskeyOptions] = useState<Record<string, unknown> | null>(null)

  const finish = useCallback((token: string | null) => {
    pendingRef.current?.resolve(token)
    pendingRef.current = null
    setPending(null)
    setPin('')
    setError(null)
    setBusy(false)
    setPasskeyOptions(null)
  }, [])

  useEffect(() => {
    setStepUpHandler(
      (challenge) =>
        new Promise<string | null>((resolve) => {
          const p = { challenge, resolve }
          pendingRef.current = p
          setPending(p)
          const canPasskey = challenge.methods.includes('PASSKEY') && webauthnSupported()
          setMethod(canPasskey ? 'PASSKEY' : 'PIN')
          // Fetch the challenge for this action up front (it also tells us the methods on file).
          securityApi
            .stepUpOptions(challenge.action)
            .then((o) => {
              setPasskeyOptions(o.passkeyOptions ?? null)
              if (!o.passkeyOptions || !o.methods.includes('PASSKEY') || !webauthnSupported())
                setMethod('PIN')
            })
            .catch(() => setMethod('PIN'))
        }),
    )
    return () => {
      setStepUpHandler(null)
      pendingRef.current?.resolve(null)
    }
  }, [])

  async function verify(body: { credential?: Record<string, unknown>; pin?: string }) {
    if (!pending) return
    setBusy(true)
    setError(null)
    try {
      const res = await securityApi.stepUpVerify({ action: pending.challenge.action, ...body })
      finish(res.stepUpToken)
    } catch (e) {
      const err = toApiError(e)
      setPin('')
      setBusy(false)
      setError(
        err.code === 'VALIDATION_FAILED'
          ? method === 'PIN'
            ? pinError(err.fieldErrors[0]?.message)
            : 'That passkey couldn’t be verified. Try again or use your PIN.'
          : errorMessage(err),
      )
    }
  }

  async function signWithPasskey() {
    if (!passkeyOptions) return
    setError(null)
    const credential = await getPasskeyAssertion(passkeyOptions)
    if (!credential) {
      setError('Passkey prompt was dismissed. Try again or use your PIN.')
      return
    }
    await verify({ credential })
  }

  const allowsPin = pending?.challenge.methods.includes('PIN') ?? false
  const allowsPasskey =
    (pending?.challenge.methods.includes('PASSKEY') ?? false) && webauthnSupported() && !!passkeyOptions

  return (
    <Dialog open={pending !== null} onOpenChange={(open) => !open && !busy && finish(null)}>
      <DialogContent
        title={pending ? ACTION_COPY[pending.challenge.action] : 'Confirm it’s you'}
        description="A quick identity check keeps large or sensitive actions safe, even if someone has your password."
      >
        <div className="flex flex-col items-center gap-5 pt-2 text-center">
          <span className="relative grid size-16 place-items-center rounded-full bg-accent/12 text-accent">
            <span className="absolute inset-0 rounded-full border border-accent/40 animate-pulse-ring" />
            <ShieldCheck aria-hidden="true" className="size-7" />
          </span>

          {method === 'PASSKEY' && allowsPasskey ? (
            <>
              <Button size="lg" className="w-full" onClick={() => void signWithPasskey()} disabled={busy}>
                <Fingerprint aria-hidden="true" />
                {busy ? 'Verifying…' : 'Use passkey'}
              </Button>
              {allowsPin && (
                <button
                  type="button"
                  className="text-sm text-muted underline-offset-4 hover:text-text hover:underline"
                  onClick={() => {
                    setMethod('PIN')
                    setError(null)
                  }}
                >
                  Use my PIN instead
                </button>
              )}
            </>
          ) : allowsPin ? (
            <>
              <PinInput
                label="Transaction PIN"
                value={pin}
                onChange={setPin}
                autoFocus
                disabled={busy}
                invalid={!!error}
                onComplete={(v) => void verify({ pin: v })}
              />
              <p className="text-xs text-muted">Enter your 6-digit transaction PIN.</p>
              {allowsPasskey && (
                <button
                  type="button"
                  className="flex items-center gap-1.5 text-sm text-muted hover:text-text"
                  onClick={() => {
                    setMethod('PASSKEY')
                    setError(null)
                  }}
                >
                  <KeyRound aria-hidden="true" className="size-4" /> Use a passkey instead
                </button>
              )}
            </>
          ) : (
            <>
              <p className="text-sm text-muted">
                You haven’t set up a transaction PIN or passkey yet. Set one up (it takes a
                minute), then try again. Nothing has been sent.
              </p>
              <Button
                className="w-full"
                onClick={() => {
                  finish(null)
                  void navigate('/settings/security')
                }}
              >
                <KeyRound aria-hidden="true" /> Set up a PIN or passkey
              </Button>
            </>
          )}

          {error && (
            <p role="alert" className="text-sm text-loss">
              {error}
            </p>
          )}

          <Button variant="ghost" className="w-full" onClick={() => finish(null)} disabled={busy}>
            Cancel
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
