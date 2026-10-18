/**
 * Payment links for QR codes (PRD F-PAY-7). We encode an https link to the send screen so any
 * phone camera can open it; we also read the older `quantgen://pay?h=…` form. Everything parsed
 * here is untrusted input: values are validated and length-capped, and only same-app paths are
 * ever navigated to.
 */
const HANDLE_RE = /^[a-z0-9.]{3,30}@qg$/
const AMOUNT_RE = /^\d{1,9}(\.\d{1,2})?$/

export interface PayLink {
  handle: string
  amount?: string
  note?: string
}

export function buildPayLink(origin: string, link: PayLink): string {
  const q = new URLSearchParams({ to: link.handle })
  if (link.amount) q.set('am', link.amount)
  if (link.note) q.set('tn', link.note.slice(0, 80))
  return `${origin}/transfer?${q.toString()}`
}

/** Parse scanned text. Returns null for anything that isn't a valid QuantGen payment link. */
export function parsePayLink(text: string, origin: string): PayLink | null {
  let url: URL
  try {
    url = new URL(text.trim())
  } catch {
    return null
  }
  let handle: string | null
  let amount: string | null
  let note: string | null
  if (url.protocol === 'quantgen:') {
    handle = url.searchParams.get('h')
    amount = url.searchParams.get('am')
    note = url.searchParams.get('tn')
  } else if (url.origin === origin && url.pathname === '/transfer') {
    handle = url.searchParams.get('to')
    amount = url.searchParams.get('am')
    note = url.searchParams.get('tn')
  } else return null
  if (!handle || !HANDLE_RE.test(handle.toLowerCase())) return null
  return {
    handle: handle.toLowerCase(),
    ...(amount && AMOUNT_RE.test(amount) ? { amount } : {}),
    ...(note ? { note: note.slice(0, 80) } : {}),
  }
}
