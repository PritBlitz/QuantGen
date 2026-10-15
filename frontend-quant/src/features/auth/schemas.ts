import { z } from 'zod'

/** F-AUTH-1 password policy, mirrored for UX. The server is the authority. */
export const PASSWORD_RULES = [
  { id: 'length', label: 'At least 8 characters', test: (v: string) => v.length >= 8 },
  { id: 'upper', label: 'An uppercase letter', test: (v: string) => /[A-Z]/.test(v) },
  { id: 'lower', label: 'A lowercase letter', test: (v: string) => /[a-z]/.test(v) },
  { id: 'digit', label: 'A number', test: (v: string) => /\d/.test(v) },
  { id: 'symbol', label: 'A symbol', test: (v: string) => /[^A-Za-z0-9]/.test(v) },
] as const

export const emailSchema = z
  .string()
  .trim()
  .min(1, 'Enter your email')
  .max(254, 'That email is too long')
  .pipe(z.email('Enter a valid email address'))

export const loginSchema = z.object({
  email: emailSchema,
  // No policy check on login: only "present", so we don't leak the policy to guessers.
  password: z.string().min(1, 'Enter your password').max(72, 'That password is too long'),
})
export type LoginValues = z.infer<typeof loginSchema>

export const registerSchema = z
  .object({
    fullName: z
      .string()
      .trim()
      .min(2, 'Enter your full name')
      .max(100, 'Keep it under 100 characters'),
    email: emailSchema,
    phone: z
      .string()
      .trim()
      .transform((v) => v.replace(/[\s-]/g, ''))
      .pipe(z.string().regex(/^\+?\d{10,15}$/, 'Enter a valid phone number, e.g. +91 98765 43210')),
    password: z
      .string()
      // Server limit (BCrypt input is capped at 72).
      .max(72, 'Keep it to 72 characters or fewer')
      .refine(
        (v) => PASSWORD_RULES.every((r) => r.test(v)),
        'Password doesn’t meet every rule below',
      ),
    confirmPassword: z.string(),
  })
  .refine((v) => v.password === v.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords don’t match',
  })
export type RegisterInput = z.input<typeof registerSchema>
export type RegisterValues = z.output<typeof registerSchema>

/** 0–4 strength score for the meter. */
export function passwordStrength(v: string): 0 | 1 | 2 | 3 | 4 {
  if (!v) return 0
  const passed = PASSWORD_RULES.filter((r) => r.test(v)).length
  if (passed < 3) return 1
  if (passed < 5) return 2
  return v.length >= 12 ? 4 : 3
}
