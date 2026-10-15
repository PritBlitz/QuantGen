const DOT = '•'

/** `123456784821` → `•••• •••• 4821` (DESIGN.md §1). */
export function maskAccountNumber(accountNumber: string): string {
  const last4 = accountNumber.slice(-4)
  return `${DOT.repeat(4)} ${DOT.repeat(4)} ${last4}`
}

/** Short form for lists and logs: `••••4821` (CLAUDE.md log masking style). */
export function maskShort(accountNumber: string): string {
  return `${DOT.repeat(4)}${accountNumber.slice(-4)}`
}

/** `123456784821` → `1234 5678 4821`. */
export function groupAccountNumber(accountNumber: string): string {
  return accountNumber.replace(/(\d{4})(?=\d)/g, '$1 ')
}
