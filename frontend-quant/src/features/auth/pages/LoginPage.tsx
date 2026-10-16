import { zodResolver } from '@hookform/resolvers/zod'
import { AlertCircle, ArrowRight, Eye, EyeOff, Loader2, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useLocation, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import { FormField } from '@/components/FormField'
import { homeFor } from '@/app/nav'
import { Button } from '@/components/ui/button'
import { useLogin } from '../session'
import { loginSchema, type LoginValues } from '../schemas'
import { applyFieldErrors } from '../serverErrors'

interface LocationState {
  from?: string
  reason?: 'idle'
}

// Dev-only seed account (PRD §10). The DEV guard removes it from production bundles.
const DEMO = import.meta.env.DEV ? { email: 'demo@quantgen.dev', password: 'Demo@1234' } : null

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const state = (location.state ?? {}) as LocationState
  const login = useLogin()
  const [showPassword, setShowPassword] = useState(false)
  const [formError, setFormError] = useState<string | null>(
    state.reason === 'idle' ? 'You were signed out after 10 minutes of inactivity.' : null,
  )

  const {
    register,
    handleSubmit,
    setError,
    setValue,
    formState: { errors },
  } = useForm<LoginValues>({ resolver: zodResolver(loginSchema), mode: 'onTouched' })

  const onSubmit = handleSubmit((values) => {
    setFormError(null)
    login.mutate(values, {
      onSuccess: (res) => {
        // Only same-app paths, never an absolute URL (open-redirect guard).
        const safe = state.from?.startsWith('/') && !state.from.startsWith('//') ? state.from : null
        // Only return to a page meant for this role (an admin never lands on the customer app).
        const isAdmin = res.user.role === 'ADMIN'
        const from = safe && safe.startsWith('/admin') === isAdmin ? safe : null
        void navigate(from ?? homeFor(res.user.role), { replace: true })
        toast.success(`Welcome back, ${res.user.fullName.split(' ')[0]}`)
      },
      onError: (err) => {
        const e = toApiError(err)
        if (e.code === 'UNAUTHORIZED') {
          // Same message for unknown email and wrong password (no account enumeration).
          setFormError('That email and password don’t match. Check them and try again.')
        } else if (e.code === 'RATE_LIMITED') {
          setFormError(errorMessage(e))
        } else if (!applyFieldErrors(e, setError, ['email', 'password'])) {
          toast.error(errorMessage(e))
        }
      },
    })
  })

  const pending = login.isPending

  return (
    <div className="w-full max-w-sm">
      <h1 className="font-display text-4xl">Welcome back</h1>
      <p className="mt-2 text-sm text-muted">Sign in to your accounts, portfolio and loans.</p>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-5" aria-busy={pending}>
        {formError && (
          <div
            role="alert"
            className="flex items-start gap-2.5 rounded-[var(--radius-control)] border border-loss/40 bg-loss/10 p-3 text-sm"
          >
            <AlertCircle aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-loss" />
            <span>{formError}</span>
          </div>
        )}

        <FormField
          label="Email"
          type="email"
          autoComplete="username"
          inputMode="email"
          autoFocus
          error={errors.email?.message}
          {...register('email')}
        />
        <FormField
          label="Password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="current-password"
          error={errors.password?.message}
          trailing={
            <Button
              variant="ghost"
              size="icon"
              className="size-9 text-muted"
              onClick={() => setShowPassword((s) => !s)}
              aria-label={showPassword ? 'Hide password' : 'Show password'}
              aria-pressed={showPassword}
            >
              {showPassword ? <EyeOff /> : <Eye />}
            </Button>
          }
          {...register('password')}
        />

        <Button type="submit" size="lg" className="w-full" disabled={pending}>
          {pending ? (
            <>
              <Loader2 className="animate-spin" aria-hidden="true" /> Signing in…
            </>
          ) : (
            <>
              Sign in <ArrowRight aria-hidden="true" />
            </>
          )}
        </Button>

        {DEMO && (
          <Button
            variant="outline"
            size="lg"
            className="w-full"
            disabled={pending}
            onClick={() => {
              setValue('email', DEMO.email, { shouldValidate: true })
              setValue('password', DEMO.password, { shouldValidate: true })
              void onSubmit()
            }}
          >
            <Sparkles aria-hidden="true" /> Use demo account
          </Button>
        )}
      </form>

      <p className="mt-8 text-sm text-muted">
        New to QuantGen?{' '}
        <Link to="/register" className="font-medium text-text underline-offset-4 hover:underline">
          Create an account
        </Link>
      </p>
    </div>
  )
}
