import { useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary, Plan, Purchase } from '../lib/types'
import { qrSvg } from '../shared/qr'
import { describe, PACKS, photosPerGb, PLAN_LABEL, packPrice, rupees, unitPrice } from './plans'
import { Button, ErrorText, inputClass } from './ui'

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

/** Preset storage sizes; any whole number of GB from MIN_GB can be typed too. */
const GB_PACKS = [5, 10, 25, 50]
const MIN_GB = 5
const MAX_MONTHS = 12

/** A + / − control for a whole number between min and max. */
function Stepper({ label, value, min, max, onChange, unit }: { label: string; value: number; min: number; max: number; onChange: (n: number) => void; unit: string }) {
  return (
    <div className="flex items-center gap-2">
      <button type="button" aria-label={`Fewer ${label}`} onClick={() => onChange(Math.max(min, value - 1))} className="size-9 rounded-full border border-line bg-white text-lg">−</button>
      <span className="w-24 text-center text-sm tabular-nums" data-testid={`${label}-count`}><b className="text-base">{value}</b> {unit}{value === 1 ? '' : 's'}</span>
      <button type="button" aria-label={`More ${label}`} onClick={() => onChange(Math.min(max, value + 1))} className="size-9 rounded-full border border-line bg-white text-lg">+</button>
    </div>
  )
}

/** Storage choices: the presets and a custom size (a whole number from MIN_GB). `allowNone` adds "Later" (0). */
function GbPicker({ label, value, onChange, allowNone }: { label: string; value: number; onChange: (gb: number) => void; allowNone?: boolean }) {
  const presets = allowNone ? [0, ...GB_PACKS] : GB_PACKS
  const custom = !presets.includes(value)
  return (
    <div>
      <div role="radiogroup" aria-label={label} className={`grid gap-1.5 text-sm ${allowNone ? 'grid-cols-5' : 'grid-cols-4'}`}>
        {presets.map((gb) => (
          <button key={gb} type="button" role="radio" aria-checked={value === gb} onClick={() => onChange(gb)}
            className={`rounded-lg border px-1 py-2.5 text-center transition-colors ${value === gb ? 'border-ink bg-stone-50 font-medium' : 'border-line hover:border-stone-400'}`}>
            {gb === 0 ? 'Later' : `${gb} GB`}
          </button>
        ))}
      </div>
      <label className="mt-2 flex items-center gap-2 text-sm">
        <span className="text-muted">Other size</span>
        <input type="number" inputMode="numeric" min={MIN_GB} step={1} aria-label={`${label}: other size in GB`} className={`${inputClass} w-24`}
          value={custom ? value : ''} placeholder={`${MIN_GB}+`}
          onChange={(e) => onChange(e.target.value === '' ? presets[0] : Math.floor(Number(e.target.value)))} />
        <span className="text-muted">GB (from {MIN_GB})</span>
      </label>
    </div>
  )
}

/**
 * Buy Main or Quick events, pay for (or switch to) a monthly pack, upgrade a Quick event to Main, add storage, keep a
 * gallery open more months or set up a client gallery. The one way to pay is McreatiK's UPI QR: scan, pay, enter the
 * transaction ID (UTR); usable at once, McreatiK checks the payment afterwards. The amount shown uses the same prices as the server, which prices every purchase again.
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
  // Months: a client gallery's open months (default 1), more months for an event (default the included months), or
  // the months a gallery has left (storage).
  const months = purchase.months ?? (purchase.item === 'CLIENT_SETUP' ? 1 : billing.prices.activeMonths)
  const unit = unitPrice(purchase.item, billing, purchase.plan, months)
  const title = TITLE[purchase.item]
  // A client gallery can be paid together with its storage for the months chosen (one gallery at a time).
  const setupGb = purchase.item === 'CLIENT_SETUP' ? purchase.storageGb ?? 0 : 0
  const perSetupGb = billing.prices.storageGb * months
  const amount = quantity * unit + setupGb * perSetupGb
  const invalid = (purchase.item === 'STORAGE' && quantity < MIN_GB) || (setupGb !== 0 && setupGb < MIN_GB)
  const label = describe(purchase, billing)
  const link = billing.upiId ? upiLink(billing.upiId, billing.payeeName, amount, label) : null
  const isEvents = purchase.item === 'MAIN_EVENT' || purchase.item === 'QUICK_EVENT'
  const max = purchase.item === 'PLAN' ? 12 : purchase.item === 'UPGRADE' || purchase.item === 'EXTEND' || setupGb > 0 ? 1 : 50
  const unitWord = purchase.item === 'PLAN' ? 'month' : purchase.item === 'STORAGE' ? 'GB'
    : purchase.item === 'CLIENT_SETUP' ? 'gallery' : 'event'
  const change = (p: Partial<Purchase>) => {
    setPurchase((old) => ({ ...old, ...p }))
    setError(null)
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
    ? `Storage for this gallery until it closes: ${rupees(billing.prices.storageGb)} per GB a month × ${months} month${months === 1 ? '' : 's'} = ${rupees(unit)} per GB. About ${photosPerGb(1)} full-quality photos per GB.`
    : purchase.item === 'CLIENT_SETUP'
      ? `A gallery for the couple only, without a live event: ${rupees(billing.prices.clientSetup)} each to set up, then storage at ${rupees(billing.prices.storageGb)} per GB a month for the months you choose.`
      : purchase.item === 'EXTEND'
        ? `Keeps the guests' and the couple's galleries open for the months you choose: ${rupees(billing.prices.storageGb)} per GB a month for the ${quantity} GB it can hold. The ${billing.prices.freeStorageGb} GB × ${billing.prices.activeMonths} months included with every Main or Quick event are already covered.`
        : purchase.item === 'UPGRADE'
    ? `Bigger than planned? Upgrade this Quick event to Main for ${rupees(billing.prices.upgrade)}. The gallery keeps working either way.`
    : purchase.item === 'PLAN'
      ? 'A monthly pack gives you events every month at a lower price per event. Unused events carry over one month.'
      : billing.unitPrice < billing.prices.main || billing.quickUnitPrice < billing.prices.quick
        ? `Extra events at your pack's price: Main ${rupees(billing.unitPrice)}, Quick ${rupees(billing.quickUnitPrice)} (normally ${rupees(billing.prices.main)} and ${rupees(billing.prices.quick)}).`
        : `A Main event is ${rupees(billing.unitPrice)}; a Quick event (up to about ${billing.prices.upgradeGuests} guests) is ${rupees(billing.quickUnitPrice)}.`

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

        {!link ? (
          <p className="mt-4 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Payments are not set up yet. Please contact McreatiK.</p>
        ) : (
          <div className="mt-4 space-y-4">
            {isEvents && (
              <div role="radiogroup" aria-label="Event type" className="grid grid-cols-2 gap-1 rounded-xl bg-stone-100 p-1 text-sm">
                {([['MAIN_EVENT', 'Main', billing.unitPrice], ['QUICK_EVENT', 'Quick', billing.quickUnitPrice]] as const).map(([item, name, price]) => (
                  <button key={item} type="button" role="radio" aria-checked={purchase.item === item} onClick={() => change({ item })}
                    className={`h-10 rounded-lg font-medium transition-colors ${purchase.item === item ? 'bg-white text-ink shadow-sm' : 'text-muted hover:text-ink'}`}>
                    {name} <span className="font-normal text-muted">· {rupees(price)}</span>
                  </button>
                ))}
              </div>
            )}
            {purchase.item === 'STORAGE' && <GbPicker label="Storage" value={quantity} onChange={(gb) => change({ quantity: gb })} />}
            {purchase.item === 'CLIENT_SETUP' && (
              <div className="space-y-3">
                <div>
                  <p className="mb-1.5 text-sm font-medium">Storage for this gallery <span className="font-normal text-muted">· {rupees(billing.prices.storageGb)} per GB a month</span></p>
                  <GbPicker label="Storage for this gallery" value={setupGb} allowNone
                    onChange={(gb) => change({ storageGb: gb, ...(gb > 0 ? { quantity: 1 } : {}) })} />
                  <p className="mt-1.5 text-xs text-muted">
                    {setupGb > 0 ? 'The new gallery starts with this storage, ready for your photos.' : 'A gallery needs storage before you can add photos. Choose it now, or add it after creating the gallery.'}
                  </p>
                </div>
                <div className="flex items-center justify-between gap-3">
                  <p className="text-sm font-medium">Open for</p>
                  <Stepper label="months" unit="month" value={months} min={1} max={MAX_MONTHS} onChange={(m) => change({ months: m })} />
                </div>
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
              {purchase.item === 'EXTEND' ? (
                <Stepper label="months" unit="month" value={months} min={1} max={MAX_MONTHS} onChange={(m) => change({ months: m })} />
              ) : purchase.item === 'STORAGE' ? <span className="text-sm font-medium">{quantity} GB</span> : max > 1 ? (
                <div className="flex items-center gap-2">
                  <button type="button" aria-label={`Fewer ${unitWord === 'gallery' ? 'galleries' : unitWord === 'GB' ? 'GB' : `${unitWord}s`}`} onClick={() => change({ quantity: Math.max(1, quantity - 1) })} className="size-9 rounded-full border border-line bg-white text-lg">−</button>
                  <span className="w-24 text-center text-sm tabular-nums" data-testid="event-count"><b className="text-base">{quantity}</b> {unitWord === 'gallery' && quantity !== 1 ? 'galleries' : unitWord === 'GB' || quantity === 1 ? unitWord : `${unitWord}s`}</span>
                  <button type="button" aria-label={`More ${unitWord === 'gallery' ? 'galleries' : unitWord === 'GB' ? 'GB' : `${unitWord}s`}`} onClick={() => change({ quantity: Math.min(max, quantity + 1) })} className="size-9 rounded-full border border-line bg-white text-lg">+</button>
                </div>
              ) : <span className="text-sm font-medium">{label}</span>}
              <p className="text-right">
                <span className="block text-xs text-muted" data-testid="breakdown">{purchase.item === 'EXTEND' ? `${formatCount(quantity)} GB × ${rupees(billing.prices.storageGb)} × ${months} month${months === 1 ? '' : 's'}`
                  : purchase.item === 'STORAGE' ? `${formatCount(quantity)} GB × ${rupees(unit)}`
                    : `${formatCount(quantity)} × ${rupees(unit)}${setupGb > 0 ? ` + ${setupGb} GB × ${rupees(billing.prices.storageGb)} × ${months} month${months === 1 ? '' : 's'}` : ''}`}</span>
                <span className="text-xl font-semibold tabular-nums" data-testid="amount">{rupees(amount)}</span>
              </p>
            </div>

            {link && (
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
                <Button type="submit" variant="primary" disabled={busy || invalid || utr.trim().length < 8} className="w-full">
                  {busy ? 'Saving…' : `I've paid ${rupees(amount)}`}
                </Button>
                <p className="text-center text-xs text-muted">It's ready at once; McreatiK confirms the payment within a day.</p>
              </form>
            )}
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
