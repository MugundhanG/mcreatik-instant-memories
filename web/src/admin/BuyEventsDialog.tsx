import { useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary } from '../lib/types'
import { qrSvg } from '../shared/qr'
import { Button, ErrorText, inputClass } from './ui'

const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

/** upi://pay link: any UPI app (GPay, PhonePe, Paytm…) opens it with payee and amount filled in. */
function upiLink(upiId: string, payee: string | null, amount: number, events: number) {
  const q = new URLSearchParams({ pa: upiId, pn: payee || 'McreatiK', am: String(amount), cu: 'INR', tn: `McreatiK ${events} event${events === 1 ? '' : 's'}` })
  return `upi://pay?${q}`
}

/**
 * Buy extra events by UPI: choose how many, scan the QR (or tap Pay on a phone), then enter the transaction ID.
 * The events can be used straight away; McreatiK checks the payment afterwards.
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
  const amount = events * billing.unitPrice
  const link = billing.upiId ? upiLink(billing.upiId, billing.payeeName, amount, events) : null

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
    <div className="fixed inset-0 z-[60] flex items-end justify-center bg-black/50 p-0 sm:items-center sm:p-4" role="dialog" aria-modal="true" aria-label="Buy events">
      <div className="max-h-[95dvh] w-full max-w-md overflow-y-auto rounded-t-2xl bg-white p-5 shadow-2xl sm:rounded-2xl">
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

        {!link ? (
          <p className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Payments are not set up yet. Please contact McreatiK.</p>
        ) : (
          <form onSubmit={submit} className="mt-4 space-y-4">
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
              {busy ? 'Saving…' : `I've paid ${rupees(amount)}: add ${events} event${events === 1 ? '' : 's'}`}
            </Button>
            <p className="text-center text-xs text-muted">Your events are ready at once; McreatiK confirms the payment within a day.</p>
          </form>
        )}
      </div>
    </div>
  )
}
