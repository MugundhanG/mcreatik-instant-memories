import { useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary, Plan, Purchase } from '../lib/types'
import { qrSvg } from '../shared/qr'
import { lastFailure, openCheckout, resetLastFailure } from '../shared/razorpay'
import { AutoUpiQr } from './AutoUpiQr'
import { describe, PACKS, photosPerGb, PLAN_LABEL, packPrice, rupees, unitPrice } from './plans'
import { Button, ErrorText, inputClass } from './ui'

type Method = 'online' | 'upi'

/** upi://pay link: any UPI app (GPay, PhonePe, Paytm…) opens it with payee and amount filled in. */
function upiLink(upiId: string, payee: string | null, amount: number, note: string) {
  const q = new URLSearchParams({ pa: upiId, pn: payee || 'McreatiK', am: String(amount), cu: 'INR', tn: `McreatiK ${note}`.slice(0, 80) })
  return `upi://pay?${q}`
}

const TITLE: Record<Purchase['item'], string> = {
  MAIN_EVENT: 'Buy events',
  QUICK_EVENT: 'Buy events',
  UPGRADE: 'Upgrade to a Main event',
  PLAN: 'Monthly pack',
  STORAGE: 'Add storage',
  CLIENT_SETUP: 'Client galleries',
  EXTEND: 'Keep more months',
}

const GB_PACKS = [5, 10, 25, 50, 100]

/**
 * Buy Main or Quick events, pay for (or switch to) a monthly pack, or upgrade a Quick event to Main. Two ways to pay:
 * - Online (card, netbanking, UPI, wallet) with Razorpay: applied as soon as Razorpay confirms.
 * - UPI QR: with Razorpay set up, a single-use QR confirmed automatically (nothing to type). Without it, McreatiK's own
 *   UPI ID: scan, pay, enter the transaction ID; usable at once, McreatiK checks the payment afterwards.
 * The amount shown uses the same prices as the server, which prices every purchase again.
 */
export function BuyEventsDialog({ billing, purchase: initial, reason, onPaid, onClose }: {
  billing: BillingSummary
  /** What to buy; the studio can change the number (and Main / Quick for events, the pack for a plan). */
  purchase?: Purchase
  /** Why it opened, e.g. "You have used your 4 events for this month." */
  reason?: string
  onPaid: () => void
  onClose: () => void
}) {
  const [purchase, setPurchase] = useState<Purchase>(() => ({ quantity: 1, ...(initial ?? { item: 'MAIN_EVENT' }) }))
  const quantity = purchase.quantity ?? 1
  const [utr, setUtr] = useState('')
  const [qr, setQr] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [paidOnline, setPaidOnline] = useState(false)
  const unit = unitPrice(purchase.item, billing, purchase.plan, purchase.months)
  const title = purchase.item === 'EXTEND' ? `Keep ${billing.prices.activeMonths} more months` : TITLE[purchase.item]
  const amount = quantity * unit
  const label = describe(purchase, billing)
  const link = billing.upiId ? upiLink(billing.upiId, billing.payeeName, amount, label) : null
  const [method, setMethod] = useState<Method>(billing.onlinePayments ? 'online' : 'upi')
  const isEvents = purchase.item === 'MAIN_EVENT' || purchase.item === 'QUICK_EVENT'
  const max = purchase.item === 'PLAN' ? 12 : purchase.item === 'UPGRADE' || purchase.item === 'EXTEND' ? 1
    : purchase.item === 'STORAGE' ? 1000 : 50
  const unitWord = purchase.item === 'PLAN' ? 'month' : purchase.item === 'STORAGE' ? 'GB'
    : purchase.item === 'CLIENT_SETUP' ? 'gallery' : 'event'
  const change = (p: Partial<Purchase>) => {
    setPurchase((old) => ({ ...old, ...p }))
    setError(null)
  }

  const payOnline = async () => {
    setBusy(true)
    setError(null)
    resetLastFailure()
    try {
      const order = await adminApi.startOnlinePayment(purchase)
      const outcome = await openCheckout(order, `${label} · Instant Memories`)
      if (outcome.kind === 'dismissed') {
        setError(lastFailure ? `${lastFailure}. No money was taken; try again or use another way.` : null)
        setBusy(false)
        return
      }
      await adminApi.confirmOnlinePayment(order.paymentId, outcome.result.razorpay_payment_id, outcome.result.razorpay_signature)
      setPaidOnline(true)
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
      await adminApi.payByUpi(purchase, utr)
      onPaid()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save the payment')
      setBusy(false)
    }
  }

  const defaultReason = purchase.item === 'STORAGE'
    ? `Storage for this gallery until it closes: ${rupees(billing.prices.storageGb)} per GB a month × ${purchase.months ?? billing.prices.activeMonths} months = ${rupees(unit)} per GB. About ${photosPerGb(1)} full-quality photos per GB.`
    : purchase.item === 'CLIENT_SETUP'
      ? `A gallery for the couple only, without a live event: ${rupees(billing.prices.clientSetup)} each to set up, then storage at ${rupees(billing.prices.storageGb)} per GB a month.`
      : purchase.item === 'EXTEND'
        ? `Keeps the guests' and the couple's galleries open for ${billing.prices.activeMonths} more months: ${rupees(billing.prices.storageGb)} per GB a month for each GB it holds (${rupees(unit)} per GB).`
        : purchase.item === 'UPGRADE'
    ? `Bigger than planned? Upgrade this Quick event to Main for ${rupees(billing.prices.upgrade)}. The gallery keeps working either way.`
    : purchase.item === 'PLAN'
      ? 'A monthly pack gives you events every month at a lower price per event. Unused events carry over one month.'
      : `A Main event is ${rupees(billing.unitPrice)}; a Quick event (up to about ${billing.prices.upgradeGuests} guests) is ${rupees(billing.prices.quick)}.`

  return (
    <div className="anim-fade-in fixed inset-0 z-[60] flex items-end justify-center bg-black/50 p-0 sm:items-center sm:p-4" role="dialog" aria-modal="true" aria-label={title}>
      <div className="anim-sheet-up max-h-[95dvh] w-full max-w-md overflow-y-auto rounded-t-2xl bg-white p-5 shadow-2xl sm:rounded-2xl">
        <div className="flex items-start justify-between gap-3">
          <div>
            <p className="text-lg font-semibold">{title}</p>
            <p className="mt-1 text-sm text-muted">{reason ?? defaultReason}</p>
          </div>
          <button type="button" onClick={onClose} aria-label="Close" className="-mt-1 -mr-1 flex size-9 items-center justify-center rounded-full text-xl text-muted hover:bg-stone-100">×</button>
        </div>

        {paidOnline ? (
          <div className="mt-6 rounded-xl bg-green-50 p-5 text-center" role="status">
            <p className="text-lg font-semibold text-green-800">Paid ✓</p>
            <p className="mt-1 text-sm text-green-900">{label}: added to your account.</p>
          </div>
        ) : !link && !billing.onlinePayments ? (
          <p className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Payments are not set up yet. Please contact McreatiK.</p>
        ) : (
          <div className="mt-4 space-y-4">
            {isEvents && (
              <div role="radiogroup" aria-label="Event type" className="grid grid-cols-2 gap-1 rounded-xl bg-stone-100 p-1 text-sm">
                {([['MAIN_EVENT', 'Main', billing.unitPrice], ['QUICK_EVENT', 'Quick', billing.prices.quick]] as const).map(([item, name, price]) => (
                  <button key={item} type="button" role="radio" aria-checked={purchase.item === item} onClick={() => change({ item })}
                    className={`h-10 rounded-lg font-medium transition-colors ${purchase.item === item ? 'bg-white text-ink shadow-sm' : 'text-muted hover:text-ink'}`}>
                    {name} <span className="font-normal text-muted">· {rupees(price)}</span>
                  </button>
                ))}
              </div>
            )}
            {purchase.item === 'STORAGE' && (
              <div role="radiogroup" aria-label="Storage" className="grid grid-cols-5 gap-1.5 text-sm">
                {GB_PACKS.map((gb) => (
                  <button key={gb} type="button" role="radio" aria-checked={quantity === gb} onClick={() => change({ quantity: gb })}
                    className={`rounded-lg border px-1 py-2 text-center transition-colors ${quantity === gb ? 'border-ink bg-stone-50 font-medium' : 'border-line hover:border-stone-400'}`}>
                    {gb} GB
                    <span className="block text-[11px] text-muted">≈ {photosPerGb(gb).toLocaleString('en-IN')}</span>
                  </button>
                ))}
              </div>
            )}
            {purchase.item === 'PLAN' && (
              <label className="block text-sm">
                <span className="font-medium">Pack</span>
                <select className={`${inputClass} mt-1.5`} value={purchase.plan ?? billing.plan} aria-label="Pack"
                  onChange={(e) => change({ plan: e.target.value as Plan })}>
                  {PACKS.map((p) => <option key={p} value={p}>{PLAN_LABEL[p]} · {rupees(packPrice(p, billing.prices))}/month</option>)}
                </select>
              </label>
            )}

            <div className="flex items-center justify-between rounded-xl bg-stone-50 p-3">
              {max > 1 ? (
                <div className="flex items-center gap-2">
                  <button type="button" aria-label={`Fewer ${unitWord === 'gallery' ? 'galleries' : unitWord === 'GB' ? 'GB' : `${unitWord}s`}`} onClick={() => change({ quantity: Math.max(1, quantity - 1) })} className="size-9 rounded-full border border-line bg-white text-lg">−</button>
                  <span className="w-24 text-center text-sm tabular-nums" data-testid="event-count"><b className="text-base">{quantity}</b> {unitWord === 'gallery' && quantity !== 1 ? 'galleries' : unitWord === 'GB' || quantity === 1 ? unitWord : `${unitWord}s`}</span>
                  <button type="button" aria-label={`More ${unitWord === 'gallery' ? 'galleries' : unitWord === 'GB' ? 'GB' : `${unitWord}s`}`} onClick={() => change({ quantity: Math.min(max, quantity + 1) })} className="size-9 rounded-full border border-line bg-white text-lg">+</button>
                </div>
              ) : <span className="text-sm font-medium">{label}</span>}
              <p className="text-right">
                <span className="block text-xs text-muted">{formatCount(quantity)} × {rupees(unit)}</span>
                <span className="text-xl font-semibold tabular-nums" data-testid="amount">{rupees(amount)}</span>
              </p>
            </div>

            {billing.onlinePayments && (
              <div role="tablist" aria-label="How to pay" className="grid grid-cols-2 gap-1 rounded-xl bg-stone-100 p-1 text-sm">
                {([['online', 'Card, netbanking, UPI'], ['upi', 'UPI QR']] as const).map(([m, name]) => (
                  <button key={m} type="button" role="tab" aria-selected={method === m} onClick={() => { setMethod(m); setError(null) }}
                    className={`h-9 rounded-lg font-medium transition-colors ${method === m ? 'bg-white text-ink shadow-sm' : 'text-muted hover:text-ink'}`}>
                    {name}
                  </button>
                ))}
              </div>
            )}

            {method === 'online' && billing.onlinePayments ? (
              <div className="space-y-3">
                <p className="text-sm text-muted">
                  Pay securely with a debit or credit card, netbanking, any UPI app or a wallet. It is added as soon as the
                  payment goes through.
                </p>
                <ErrorText>{error}</ErrorText>
                <Button variant="primary" onClick={payOnline} disabled={busy} className="w-full">
                  {busy ? 'Opening secure payment…' : `Pay ${rupees(amount)}`}
                </Button>
                <p className="text-center text-xs text-muted">Payments are processed by Razorpay. McreatiK never sees your card or bank details.</p>
              </div>
            ) : billing.onlinePayments ? (
              <AutoUpiQr key={JSON.stringify(purchase)} purchase={purchase} label={label} amount={amount} onPaid={() => {
                setPaidOnline(true)
                setTimeout(onPaid, 1200)
              }} />
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
                  {busy ? 'Saving…' : `I've paid ${rupees(amount)}`}
                </Button>
                <p className="text-center text-xs text-muted">It's ready at once; McreatiK confirms the payment within a day.</p>
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
