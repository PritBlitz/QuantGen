import { zodResolver } from '@hookform/resolvers/zod'
import { ArrowRight, Eye, EyeOff, Loader2 } from 'lucide-react'
import { useState } from 'react'
import { useForm, useWatch } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'
import { errorMessage, toApiError } from '@/api/errors'
import { FormField } from '@/components/FormField'
import { Button } from '@/components/ui/button'
import { PasswordStrength } from '../components/PasswordStrength'
import { useRegister } from '../session'
import { registerSchema, type RegisterInput, type RegisterValues } from '../schemas'
import { applyFieldErrors } from '../serverErrors'

export function RegisterPage() {
  const navigate = useNavigate()
  const signUp = useRegister()
  const [showPassword, setShowPassword] = useState(false)

  const {
    register,
    handleSubmit,
    setError,
    control,
    formState: { errors },
  } = useForm<RegisterInput, unknown, RegisterValues>({
    resolver: zodResolver(registerSchema),
    mode: 'onTouched',
    defaultValues: { fullName: '', email: '', phone: '', password: '', confirmPassword: '' },
  })
  const password = useWatch({ control, name: 'password' }) ?? ''

  const onSubmit = handleSubmit(({ fullName, email, phone, password }) => {
    // confirmPassword is client-only and never sent.
    signUp.mutate(
      { fullName, email, phone, password },
      {
        onSuccess: (res) => {
          toast.success(`Welcome to QuantGen, ${res.user.fullName.split(' ')[0]}`, {
            description: 'Your savings account is ready.',
          })
          void navigate('/dashboard', { replace: true })
        },
        onError: (err) => {
          const e = toApiError(err)
          if (!applyFieldErrors(e, setError, ['fullName', 'email', 'phone', 'password'])) {
            toast.error(errorMessage(e))
          }
        },
      },
    )
  })

  const pending = signUp.isPending
  const passwordToggle = (
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
  )

  return (
    <div className="w-full max-w-sm">
      <h1 className="font-display text-4xl">Open your account</h1>
      <p className="mt-2 text-sm text-muted">
        Two minutes. You get a savings account straight away.
      </p>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-5" aria-busy={pending}>
        <FormField
          label="Full name"
          autoComplete="name"
          autoFocus
          error={errors.fullName?.message}
          {...register('fullName')}
        />
        <FormField
          label="Email"
          type="email"
          autoComplete="email"
          inputMode="email"
          error={errors.email?.message}
          {...register('email')}
        />
        <FormField
          label="Phone"
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          placeholder="+91 98765 43210"
          error={errors.phone?.message}
          {...register('phone')}
        />
        <div className="space-y-3">
          <FormField
            label="Password"
            type={showPassword ? 'text' : 'password'}
            autoComplete="new-password"
            error={errors.password?.message}
            trailing={passwordToggle}
            aria-describedby="password-strength"
            {...register('password')}
          />
          <PasswordStrength id="password-strength" value={password} />
        </div>
        <FormField
          label="Confirm password"
          type={showPassword ? 'text' : 'password'}
          autoComplete="new-password"
          error={errors.confirmPassword?.message}
          {...register('confirmPassword')}
        />

        <Button type="submit" size="lg" className="w-full" disabled={pending}>
          {pending ? (
            <>
              <Loader2 className="animate-spin" aria-hidden="true" /> Creating your account…
            </>
          ) : (
            <>
              Create account <ArrowRight aria-hidden="true" />
            </>
          )}
        </Button>
      </form>

      <p className="mt-8 text-sm text-muted">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-text underline-offset-4 hover:underline">
          Sign in
        </Link>
      </p>
    </div>
  )
}
