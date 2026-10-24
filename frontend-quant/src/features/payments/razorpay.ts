/**
 * Razorpay Checkout (PRD §7.9, test mode). The script is loaded on demand from Razorpay's CDN
 * (allowed in the CSP only for this origin). The browser never decides a payment succeeded: it
 * hands the payment id + signature to our server, which verifies the HMAC and fetches the payment
 * from Razorpay before crediting.
 */
const SRC = 'https://checkout.razorpay.com/v1/checkout.js'

export interface CheckoutResult {
  paymentId: string
  signature: string
}

interface RazorpayCtor {
  new (options: Record<string, unknown>): {
    open: () => void
    on: (event: string, cb: (r: unknown) => void) => void
  }
}

let loading: Promise<RazorpayCtor> | null = null

function load(): Promise<RazorpayCtor> {
  const existing = (window as unknown as { Razorpay?: RazorpayCtor }).Razorpay
  if (existing) return Promise.resolve(existing)
  loading ??= new Promise((resolve, reject) => {
    const s = document.createElement('script')
    s.src = SRC
    s.async = true
    s.onload = () => {
      const ctor = (window as unknown as { Razorpay?: RazorpayCtor }).Razorpay
      if (ctor) resolve(ctor)
      else reject(new Error('Razorpay failed to load'))
    }
    s.onerror = () => {
      loading = null
      reject(new Error('Razorpay failed to load'))
    }
    document.head.append(s)
  })
  return loading
}

/** True in dev mock mode: there is no real gateway, so a simulated checkout is used instead. */
export const useSimulatedCheckout = import.meta.env.DEV && import.meta.env.VITE_USE_MOCKS === 'true'

/**
 * Open the real Checkout for a server-created order. Resolves with the ids to verify, or null
 * if the user closes the window. Amount comes from the order (never passed from the client).
 */
export async function openCheckout(opts: {
  keyId: string
  orderId: string
  name: string
  email: string
}): Promise<CheckoutResult | null> {
  const Razorpay = await load()
  return new Promise((resolve) => {
    let settled = false
    const rz = new Razorpay({
      key: opts.keyId,
      order_id: opts.orderId,
      name: 'QuantGen',
      description: 'Add money (test mode)',
      prefill: { name: opts.name, email: opts.email },
      theme: { color: '#5b3fd6' },
      handler: (r: { razorpay_payment_id: string; razorpay_signature: string }) => {
        settled = true
        resolve({ paymentId: r.razorpay_payment_id, signature: r.razorpay_signature })
      },
      modal: {
        ondismiss: () => {
          if (!settled) resolve(null)
        },
      },
    })
    rz.on('payment.failed', () => {
      // Razorpay keeps the modal open so the user can retry; a later success still resolves.
    })
    rz.open()
  })
}
