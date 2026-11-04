import type { OnlineOrder } from '../lib/types'

/** What Razorpay Checkout hands back after a successful payment (checked again by our server). */
export interface CheckoutSuccess {
  razorpay_payment_id: string
  razorpay_order_id: string
  razorpay_signature: string
}

interface RazorpayInstance {
  open(): void
  on(event: 'payment.failed', handler: (r: { error?: { description?: string } }) => void): void
}

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => RazorpayInstance
  }
}

let loading: Promise<void> | null = null

/** Loads Razorpay's checkout.js only when someone is about to pay (never on other pages). */
export function loadRazorpay(): Promise<void> {
  if (window.Razorpay) return Promise.resolve()
  if (loading) return loading
  loading = new Promise<void>((resolve, reject) => {
    const s = document.createElement('script')
    s.src = 'https://checkout.razorpay.com/v1/checkout.js'
    s.async = true
    s.onload = () => {
      loading = null
      resolve()
    }
    s.onerror = () => {
      loading = null
      reject(new Error("The payment window couldn't load. Check the internet, or pay by UPI QR."))
    }
    document.body.appendChild(s)
  })
  return loading
}

export type CheckoutOutcome =
  | { kind: 'paid'; result: CheckoutSuccess }
  /** Closed without paying; {@link lastFailure} says why, if a payment was declined first. */
  | { kind: 'dismissed' }

/** Opens Razorpay Checkout (card, netbanking, UPI, wallet) for an order our server created. */
export async function openCheckout(order: OnlineOrder, description: string): Promise<CheckoutOutcome> {
  await loadRazorpay()
  const Razorpay = window.Razorpay
  if (!Razorpay) throw new Error("The payment window couldn't load. Please pay by UPI QR.")
  return new Promise<CheckoutOutcome>((resolve) => {
    let settled = false
    const done = (o: CheckoutOutcome) => {
      if (!settled) {
        settled = true
        resolve(o)
      }
    }
    const rz = new Razorpay({
      key: order.keyId,
      order_id: order.razorpayOrderId,
      amount: order.amountPaise,
      currency: order.currency,
      name: 'McreatiK Instant Memories',
      description,
      prefill: { name: order.studioName, email: order.email },
      notes: { paymentId: order.paymentId },
      theme: { color: '#1c1917' },
      handler: (r: CheckoutSuccess) => done({ kind: 'paid', result: r }),
      modal: { ondismiss: () => done({ kind: 'dismissed' }), confirm_close: true },
    })
    // A failed attempt keeps the window open so the studio can try another card/bank; we only note it.
    rz.on('payment.failed', (r) => {
      lastFailure = r.error?.description ?? 'The payment was declined'
    })
    rz.open()
  })
}

/** The last decline reason Razorpay reported (shown if the studio then closes the window). */
export let lastFailure: string | null = null

export function resetLastFailure() {
  lastFailure = null
}
