import { useEffect, useRef, useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import type { UpiQrOrder } from '../lib/types'
import { qrSvg } from '../shared/qr'
import { Button, ErrorText } from './ui'

/** How often the dialog asks the server "paid yet?" (the server checks with Razorpay). */
export const POLL_MS = 3000

const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`
const plural = (n: number) => `${n} event${n === 1 ? '' : 's'}`

function remaining(expiresAt: string, now: number) {
  const s = Math.max(0, Math.floor((Date.parse(expiresAt) - now) / 1000))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

/**
 * UPI QR confirmed automatically: a single-use Razorpay QR for the exact amount. The studio scans it with any UPI app
 * and the events are added within seconds; there is no transaction ID to type and nothing for McreatiK to check.
 * Give it {@code key={events}}: a different number of events needs a new QR (the old one is closed on unmount).
 */
export function AutoUpiQr({ events, amount, onPaid }: { events: number; amount: number; onPaid: (events: number) => void }) {
  const [order, setOrder] = useState<UpiQrOrder | null>(null)
  const [svg, setSvg] = useState<string | null>(null)
  const [state, setState] = useState<'idle' | 'creating' | 'waiting' | 'expired'>('idle')
  const [error, setError] = useState<string | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const open = useRef<string | null>(null) // the QR to stop if the dialog closes
  const paidRef = useRef(onPaid)
  useEffect(() => {
    paidRef.current = onPaid
  }, [onPaid])

  const close = () => {
    if (open.current) void adminApi.cancelOnlinePayment(open.current).catch(() => undefined)
    open.current = null
  }

  // Closing the dialog stops the QR (a payment already made still counts on the server).
  useEffect(() => close, [])

  const show = async () => {
    setState('creating')
    setError(null)
    try {
      const o = await adminApi.startUpiQr(events)
      open.current = o.paymentId
      setOrder(o)
      setSvg(o.upiLink ? await qrSvg(o.upiLink) : null)
      setNow(Date.now())
      setState('waiting')
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error ? err.message : 'Could not create the QR')
      setState('idle')
    }
  }

  useEffect(() => {
    if (state !== 'waiting' || !order) return
    let stopped = false
    const tick = setInterval(() => setNow(Date.now()), 1000)
    const poll = setInterval(async () => {
      try {
        const s = await adminApi.onlinePaymentStatus(order.paymentId)
        if (stopped) return
        if (s.status === 'PAID') {
          open.current = null
          stopped = true
          paidRef.current(s.events)
        } else if (s.status === 'EXPIRED') {
          close()
          setState('expired')
        }
      } catch {
        // a dropped connection: keep asking
      }
    }, POLL_MS)
    return () => {
      stopped = true
      clearInterval(tick)
      clearInterval(poll)
    }
  }, [state, order])

  if (state === 'waiting' && order) {
    return (
      <div className="space-y-3" data-testid="auto-upi">
        <div className="flex items-center gap-4">
          {svg ? (
            <div className="size-40 shrink-0 rounded-lg border border-line p-1.5 [&>svg]:size-full" role="img" aria-label={`UPI QR for ${rupees(order.amount)}`}
              // SVG generated locally from Razorpay's UPI link.
              dangerouslySetInnerHTML={{ __html: svg }} />
          ) : order.imageUrl ? (
            <img src={order.imageUrl} alt={`UPI QR for ${rupees(order.amount)}`} className="size-40 shrink-0 rounded-lg border border-line object-contain" />
          ) : null}
          <div className="min-w-0 text-sm">
            <p className="font-medium">Scan and pay {rupees(order.amount)}</p>
            <p className="mt-1 text-muted">With GPay, PhonePe, Paytm or any UPI app.</p>
            {order.upiLink && (
              <a href={order.upiLink} className="mt-2 inline-flex h-9 items-center rounded-lg bg-ink px-3.5 text-sm font-medium text-white">Pay with UPI app</a>
            )}
          </div>
        </div>
        <p className="flex items-center gap-2 rounded-lg bg-stone-50 p-3 text-sm" role="status">
          <span className="size-2 shrink-0 animate-pulse rounded-full bg-amber-500" />
          <span>Waiting for your payment… {plural(order.events)} {order.events === 1 ? 'is' : 'are'} added automatically. <span className="text-muted tabular-nums">QR valid {remaining(order.expiresAt, now)}</span></span>
        </p>
      </div>
    )
  }

  return (
    <div className="space-y-3">
      <p className="text-sm text-muted">
        {state === 'expired' ? 'That QR has expired and no payment was received. Make a new one to pay.'
          : 'Scan a QR with any UPI app. Your events are added automatically the moment you pay; there is nothing to type.'}
      </p>
      <ErrorText>{error}</ErrorText>
      <Button variant="primary" className="w-full" disabled={state === 'creating'} onClick={show}>
        {state === 'creating' ? 'Making your QR…' : `${state === 'expired' ? 'New' : 'Show'} UPI QR for ${rupees(amount)}`}
      </Button>
    </div>
  )
}
