import { useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary } from '../lib/types'
import { qrSvg } from '../shared/qr'
import { lastFailure, openCheckout, resetLastFailure } from '../shared/razorpay'
import { Button, ErrorText, inputClass } from './ui'

type Method = 'online' | 'upi'

const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

/** upi://pay link: any UPI app (GPay, PhonePe, Paytm…) opens it with payee and amount filled in. */
function upiLink(upiId: string, payee: string | null, amount: number, events: number) {
  const q = new URLSearchParams({ pa: upiId, pn: payee || 'McreatiK', am: String(amount), cu: 'INR', tn: `McreatiK ${events} event${events === 1 ? '' : 's'}` })
  return `upi://pay?${q}`
}

/**
 * Buy extra events. Two ways:
 * - Pay online (card, netbanking, UPI, wallet) with Razorpay: the events are added as soon as Razorpay confirms.
 * - UPI QR: scan, pay, enter the transaction ID; usable at once, McreatiK checks the payment afterwards.
 */
export function BuyEventsDialog({ billing, reason, onPaid, onClose }: {
  billing: BillingSummary
  /** Why it opened, e.g. "You have used your 4 events for this month." */
  reason?: string
  onPaid: () => void
  onClose: () => void
}) {
  const [events, setEvents] = useState(1)
  const [utr, setUtr] = useState('')
  const [qr, setQr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [paidOnline, setPaidOnline] = useState<number | null>(null)
  const amount = events * billing.unitPrice
  const link = billing.upiId ? upiLink(billing.upiId, billing.payeeName, amount, events) : null
  const [method, setMethod] = useState<Method>(billing.onlinePayments ? 'online' : 'upi')
  const plural = (n: number) => `${n} event${n === 1 ? '' : 's'}`

  const payOnline = async () => {
    setBusy(true)
    setError(null)
    resetLastFailure()
    try {
      const order = await adminApi.startOnlinePayment(events)
      const outcome = await openCheckout(order, `${plural(order.events)} · Instant Memories`)
      if (outcome.kind === 'dismissed') {
        setError(lastFailure ? `${lastFailure}. No money was taken for events; try again or use another way.` : null)
        setBusy(false)
        return
      }
      await adminApi.confirmOnlinePayment(order.paymentId, outcome.result.razorpay_payment_id, outcome.result.razorpay_signature)
      setPaidOnline(order.events)
      setTimeout(onPaid, 1200) // a moment to read "Paid", then carry on (e.g. create the event)
    } catch (err) {
      setError(err instanceof ApiError || err instanceof Error ? err.message : 'The payment could not be completed')
      setBusy(false)
    }
  }

  useEffect(() => {
    if (!link) return
    let active = true
    qrSvg(link).then((s) => active && setQr(s))
    return () => {
      active = false
    }
  }, [link])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await adminApi.payForEvents(events, utr)
      onPaid()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save the payment')
      setBusy(false)
    }
  }

  return (
    <div className="anim-fade-in fixed inset-0 z-[60] flex items-end justify-center bg-black/50 p-0 sm:items-center sm:p-4" role="dialog" aria-modal="true" aria-label="Buy events">
      <div className="anim-sheet-up max-h-[95dvh] w-full max-w-md overflow-y-auto rounded-t-2xl bg-white p-5 shadow-2xl sm:rounded-2xl">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-lg font-semibold">Buy events</p>
            <p className="mt-1 text-sm text-muted">
              {reason ?? (billing.plan === 'MONTHLY'
                ? `Your plan includes ${billing.monthlyQuota} events a month. Extra events are ${rupees(billing.unitPrice)} each.`
                : `Each event is ${rupees(billing.unitPrice)}.`)}
            </p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="-mt-1 -mr-1 flex size-9 items-center justify-center rounded-full text-xl text-muted hover:bg-stone-100">×</button>
        </div>

        {paidOnline !== null ? (
          <div className="mt-6 rounded-xl bg-green-50 p-5 text-center" role="status">
            <p className="text-lg font-semibold text-green-800">Paid ✓</p>
            <p className="mt-1 text-sm text-green-900">{plural(paidOnline)} added to your account.</p>
          </div>
        ) : !link && !billing.onlinePayments ? (
          <p className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Payments are not set up yet. Please contact McreatiK.</p>
        ) : (
          <div className="mt-4 space-y-4">
            <div className="flex items-center justify-between rounded-xl bg-stone-50 p-3">
              <div className="flex items-center gap-2">
                <button type="button" aria-label="Fewer events" onClick={() => setEvents((n) => Math.max(1, n - 1))} className="size-9 rounded-full border border-line bg-white text-lg">−</button>
                <span className="w-24 text-center text-sm tabular-nums" data-testid="event-count"><b className="text-base">{events}</b> event{events === 1 ? '' : 's'}</span>
                <button type="button" aria-label="More events" onClick={() => setEvents((n) => Math.min(50, n + 1))} className="size-9 rounded-full border border-line bg-white text-lg">+</button>
              </div>
              <p className="text-right">
                <span className="block text-xs text-muted">{formatCount(events)} × {rupees(billing.unitPrice)}</span>
                <span className="text-xl font-semibold tabular-nums" data-testid="amount">{rupees(amount)}</span>
              </p>
            </div>

            {billing.onlinePayments && link && (
              <div role="tablist" aria-label="How to pay" className="grid grid-cols-2 gap-1 rounded-xl bg-stone-100 p-1 text-sm">
                {([['online', 'Card, netbanking, UPI'], ['upi', 'UPI QR']] as const).map(([m, label]) => (
                  <button key={m} type="button" role="tab" aria-selected={method === m} onClick={() => { setMethod(m); setError(null) }}
                    className={`h-9 rounded-lg font-medium transition-colors ${method === m ? 'bg-white text-ink shadow-sm' : 'text-muted hover:text-ink'}`}>
                    {label}
                  </button>
                ))}
              </div>
            )}

            {method === 'online' && billing.onlinePayments ? (
              <div className="space-y-3">
                <p className="text-sm text-muted">
                  Pay securely with a debit or credit card, netbanking, any UPI app or a wallet. Your events are added as soon as the
                  payment goes through.
                </p>
                <ErrorText>{error}</ErrorText>
                <Button variant="primary" onClick={payOnline} disabled={busy} className="w-full">
                  {busy ? 'Opening secure payment…' : `Pay ${rupees(amount)} for ${plural(events)}`}
                </Button>
                <p className="text-center text-xs text-muted">Payments are processed by Razorpay. McreatiK never sees your card or bank details.</p>
              </div>
            ) : link ? (
              <form onSubmit={submit} className="space-y-4">
                <div className="flex items-center gap-4">
                  <div className="size-36 shrink-0 rounded-lg border border-line p-1.5 [&>svg]:size-full" role="img" aria-label={`UPI QR for ${rupees(amount)}`}
                    // SVG generated locally from our own UPI link.
                    dangerouslySetInnerHTML={qr ? { __html: qr } : undefined} />
                  <div className="min-w-0 text-sm">
                    <p className="font-medium">1. Pay {rupees(amount)} by UPI</p>
                    <p className="mt-1 text-muted">Scan with any UPI app, or on this phone:</p>
                    <a href={link} className="mt-2 inline-flex h-9 items-center rounded-lg bg-ink px-3.5 text-sm font-medium text-white">Pay with UPI app</a>
                    <p className="mt-2 truncate text-xs text-muted" title={billing.upiId ?? ''}>To {billing.payeeName ? `${billing.payeeName} · ` : ''}{billing.upiId}</p>
                  </div>
                </div>

                <label className="block text-sm">
                  <span className="font-medium">2. Enter the UPI transaction ID</span>
                  <span className="block text-xs text-muted">The 12-digit UTR / reference number shown in your UPI app after paying.</span>
                  <input className={`${inputClass} mt-1.5 font-mono`} required inputMode="numeric" value={utr} onChange={(e) => setUtr(e.target.value)} placeholder="e.g. 418299301122" />
                </label>
                <ErrorText>{error}</ErrorText>
                <Button type="submit" variant="primary" disabled={busy || utr.trim().length < 8} className="w-full">
                  {busy ? 'Saving…' : `I've paid ${rupees(amount)}: add ${plural(events)}`}
                </Button>
                <p className="text-center text-xs text-muted">Your events are ready at once; McreatiK confirms the payment within a day.</p>
              </form>
            ) : null}
            <p className="text-center text-xs text-muted">
              <a href="/pricing" target="_blank" rel="noreferrer" className="underline underline-offset-2">Pricing</a>
              {' · '}
              <a href="/refunds" target="_blank" rel="noreferrer" className="underline underline-offset-2">Refunds &amp; cancellation</a>
              {' · '}
              <a href="/terms" target="_blank" rel="noreferrer" className="underline underline-offset-2">Terms</a>
            </p>
          </div>
        )}
      </div>
    </div>
  )
}
