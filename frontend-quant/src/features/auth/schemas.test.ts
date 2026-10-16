import { describe, expect, it } from 'vitest'
import { registerSchema } from './schemas'

const base = {
  fullName: 'Test User',
  email: 'test@example.com',
  phone: '+91 98123 45678',
  confirmPassword: '',
}
const passwordOk = (password: string) =>
  registerSchema.safeParse({ ...base, password, confirmPassword: password }).success

// Must agree with the backend's StrongPassword validator, or the form lets through a
// password the server then rejects with a 400 (this happened with "Tester 123").
describe('password policy mirrors the backend', () => {
  it.each([
    ['Tester@123', true],
    ['Tester 123', false], // a space is not a symbol on the server
    ['Tester123', false], // no symbol at all
    ['tester@123', false], // no uppercase
    ['TESTER@123', false], // no lowercase
    ['Tester@abc', false], // no digit
    ['Tes@1', false], // too short
    ['Ünïcødé@1', true], // Unicode upper/lower letters count, as on the server
  ])('%s → %s', (password, ok) => {
    expect(passwordOk(password)).toBe(ok)
  })

  it('caps the password at 72 UTF-8 bytes, not 72 characters', () => {
    expect(passwordOk(`Aa1!${'x'.repeat(68)}`)).toBe(true) // 72 bytes
    expect(passwordOk(`Aa1!${'x'.repeat(69)}`)).toBe(false) // 73 bytes
    expect(passwordOk(`Aa1!${'é'.repeat(35)}`)).toBe(false) // 39 chars but 74 bytes
  })

  it('sends the phone with spaces and dashes removed, as the server requires', () => {
    const r = registerSchema.parse({
      ...base,
      password: 'Tester@123',
      confirmPassword: 'Tester@123',
    })
    expect(r.phone).toBe('+919812345678')
  })
})
