import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { timeAgo } from '../lib/format'
import type { BillingSettings, EventPayment, Plan, StudioBilling } from '../lib/types'
import { isPack, PACKS, PLAN_LABEL } from './plans'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { useConfirm } from './useConfirm'

const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

/** What a payment bought, in a few words. */
function paymentFor(p: EventPayment): string {
  const n = p.events
  switch (p.item) {
    case 'QUICK_EVENT': return `${n} Quick event${n === 1 ? '' : 's'}`
    case 'UPGRADE': return `Upgrade to Main${p.eventName ? `: ${p.eventName}` : ''}`
    case 'PLAN': return `${n} month${n === 1 ? '' : 's'} · ${p.plan ? PLAN_LABEL[p.plan] : 'pack'}`
    case 'STORAGE': return `${n} GB storage${p.eventName ? `: ${p.eventName}` : ''}`
    case 'CLIENT_SETUP': return `${n} client galler${n === 1 ? 'y' : 'ies'}`
    case 'EXTEND': return `More months (${n} GB)${p.eventName ? `: ${p.eventName}` : ''}`
    default: return `${n} Main event${n === 1 ? '' : 's'}`
  }
}

/**
 * UPI payments studios reported, waiting for McreatiK to match them with the bank statement. The events were
 * usable straight away; rejecting takes them back.
 */
export function PaymentsToCheck({ onChange }: { onChange: () => void }) {
  const [payments, setPayments] = useState<EventPayment[]>([])
  const [confirm, confirmDialog] = useConfirm()
  const load = useCallback(() => adminApi.payments('PENDING').then(setPayments).catch(() => {}), [])
  useEffect(() => {
    load()
  }, [load])

  const approve = async (p: EventPayment) => {
    await adminApi.approvePayment(p.id)
    load()
    onChange()
  }
  const reject = async (p: EventPayment) => {
    const ok = await confirm({
      title: `Reject ${rupees(p.amount)} from ${p.studioName}?`,
      body: `Only if transaction ${p.utr} is not in your bank statement. What they bought (${paymentFor(p)}) is taken back.`,
      confirmLabel: 'Reject payment',
      danger: true,
    })
    if (!ok) return
    await adminApi.rejectPayment(p.id, 'Payment not received')
    load()
    onChange()
  }

  if (payments.length === 0) return null
  return (
    <Card title={`Payments to check (${payments.length})`} className="border-amber-200">
      {confirmDialog}
      <p className="mb-3 text-sm text-muted">Find each transaction ID (UTR) and amount in your bank or UPI app, then approve it.</p>
      <ul className="divide-y divide-line" data-testid="payments-to-check">
        {payments.map((p) => (
          <li key={p.id} className="flex flex-wrap items-center gap-x-6 gap-y-2 py-3">
            <div className="min-w-48 flex-1">
              <p className="font-medium">{p.studioName} · <span className="tabular-nums">{rupees(p.amount)}</span></p>
              <p className="text-xs text-muted">{paymentFor(p)} · {p.events} × {rupees(p.unitPrice)} · {timeAgo(p.createdAt)}</p>
            </div>
            <p className="font-mono text-sm" title="UPI transaction ID">UTR {p.utr}</p>
            <div className="flex gap-2">
              <Button variant="primary" onClick={() => approve(p)}>Received</Button>
              <Button variant="danger" onClick={() => reject(p)}>Not received</Button>
            </div>
          </li>
        ))}
      </ul>
    </Card>
  )
}

/** Where studios pay (UPI) and the prices. */
export function BillingSettingsCard() {
  const [s, setS] = useState<BillingSettings | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  useEffect(() => {
    adminApi.billingSettings().then(setS).catch(() => {})
  }, [])
  if (!s) return null

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      setS(await adminApi.updateBillingSettings(s))
      setSaved(true)
      setTimeout(() => setSaved(false), 2500)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not save')
    }
  }
  const num = (k: keyof BillingSettings) => (e: React.ChangeEvent<HTMLInputElement>) => setS({ ...s, [k]: Number(e.target.value) || 0 })

  return (
    <Card title="Payments & prices">
      <form onSubmit={save} className="grid gap-4 sm:grid-cols-2">
        <Field label="Your UPI ID" hint="Studios pay here (QR and UPI app button)">
          <input className={inputClass} value={s.upiId ?? ''} onChange={(e) => setS({ ...s, upiId: e.target.value })} placeholder="mcreatik@okicici" />
        </Field>
        <Field label="Name shown in the UPI app">
          <input className={inputClass} value={s.payeeName ?? ''} onChange={(e) => setS({ ...s, payeeName: e.target.value })} placeholder="McreatiK Studios" />
        </Field>
        <Field label="Main event: single price (₹)">
          <input className={inputClass} inputMode="numeric" value={s.perEventPrice} onChange={num('perEventPrice')} />
        </Field>
        <Field label="Quick event: single price (₹)">
          <input className={inputClass} inputMode="numeric" value={s.quickPrice} onChange={num('quickPrice')} />
        </Field>
        <Field label="Main pack · 2 events (₹/month)">
          <input className={inputClass} inputMode="numeric" value={s.main2Price} onChange={num('main2Price')} />
        </Field>
        <Field label="Main pack · 4 events (₹/month)">
          <input className={inputClass} inputMode="numeric" value={s.main4Price} onChange={num('main4Price')} />
        </Field>
        <Field label="Quick pack · 2 events (₹/month)">
          <input className={inputClass} inputMode="numeric" value={s.quick2Price} onChange={num('quick2Price')} />
        </Field>
        <Field label="Quick pack · 4 events (₹/month)">
          <input className={inputClass} inputMode="numeric" value={s.quick4Price} onChange={num('quick4Price')} />
        </Field>
        <Field label="Upgrade a Quick event to Main (₹)">
          <input className={inputClass} inputMode="numeric" value={s.upgradePrice} onChange={num('upgradePrice')} />
        </Field>
        <Field label="Offer the upgrade after (guests)" hint="Unique guests on a Quick event">
          <input className={inputClass} inputMode="numeric" value={s.upgradeGuests} onChange={num('upgradeGuests')} />
        </Field>
        <Field label="Storage (₹ per GB per month)">
          <input className={inputClass} inputMode="numeric" value={s.storageGbPrice} onChange={num('storageGbPrice')} />
        </Field>
        <Field label="Free storage with a live event (GB)">
          <input className={inputClass} inputMode="numeric" value={s.freeStorageGb} onChange={num('freeStorageGb')} />
        </Field>
        <Field label="Client gallery setup (₹ each)">
          <input className={inputClass} inputMode="numeric" value={s.clientSetupPrice} onChange={num('clientSetupPrice')} />
        </Field>
        <Field label="Galleries stay open (months)" hint="Then deleted 30 days later unless kept longer">
          <input className={inputClass} inputMode="numeric" value={s.activeMonths} onChange={num('activeMonths')} />
        </Field>
        <Field label="Older Monthly package: extra event (₹)">
          <input className={inputClass} inputMode="numeric" value={s.extraEventPrice} onChange={num('extraEventPrice')} />
        </Field>
        <Field label="Older Monthly package (₹/month)" hint="Only for studios still on it">
          <input className={inputClass} inputMode="numeric" value={s.monthlyPrice} onChange={num('monthlyPrice')} />
        </Field>
        <div className="flex items-end justify-end gap-3">
          {saved && <span role="status" className="text-sm text-emerald-700">Saved ✓</span>}
          <Button type="submit" variant="primary">Save</Button>
        </div>
      </form>
      <div className="mt-2"><ErrorText>{error}</ErrorText></div>
    </Card>
  )
}

/** "Main events  − 3 +": the count a studio has to use, with one-click give / take back (never below 0). */
function CreditStepper({ label, value, busy, onStep }: { label: string; value: number; busy: boolean; onStep: (delta: 1 | -1) => void }) {
  const btn = 'flex size-7 items-center justify-center rounded-md border border-line bg-white text-base leading-none font-medium hover:bg-stone-100 disabled:opacity-40'
  return (
    <span className="inline-flex items-center gap-1.5" role="group" aria-label={label}>
      <span className="text-xs text-muted">{label}</span>
      <button type="button" className={btn} aria-label={`Take back one: ${label}`} disabled={busy || value <= 0} onClick={() => onStep(-1)}>−</button>
      <b className={`min-w-6 text-center tabular-nums ${value < 0 ? 'text-red-700' : ''}`} data-testid={`count-${label}`}>{value}</b>
      <button type="button" className={btn} aria-label={`Give one: ${label}`} disabled={busy} onClick={() => onStep(1)}>+</button>
    </span>
  )
}

/**
 * One studio's plan, this month's usage, pack paid-until date and the events it has to use; McreatiK can give or take
 * back Main events, Quick events and client galleries with + / − (e.g. a free trial, or a refund) and set the
 * paid-until date after a renewal paid outside the app.
 */
export function StudioPlan({ billing, onChange }: { billing: StudioBilling; onChange: () => void }) {
  const [quota, setQuota] = useState(String(billing.monthlyQuota))
  const [busy, setBusy] = useState(false)
  const update = async (input: { plan?: Plan; monthlyQuota?: number; addCredits?: number; addQuickCredits?: number; addClientCredits?: number; planPaidUntil?: string }) => {
    setBusy(true)
    try {
      await adminApi.updateStudioBilling(billing.studioId, input)
      onChange()
    } finally {
      setBusy(false)
    }
  }
  const packLeft = isPack(billing.plan) && billing.packActive ? Math.max(0, billing.monthlyQuota - billing.usedThisMonth) : 0
  const plans: Plan[] = ['PER_EVENT', ...PACKS, 'UNLIMITED', ...(billing.plan === 'MONTHLY' ? ['MONTHLY' as Plan] : [])]
  return (
    <div className="flex w-full flex-wrap items-center gap-2 rounded-lg bg-stone-50 px-3 py-2 text-sm" data-testid={`plan-${billing.studioId}`}>
      <select aria-label="Plan" value={billing.plan} onChange={(e) => update({ plan: e.target.value as Plan })}
        className="h-8 rounded-md border border-line bg-white px-2 text-sm">
        {plans.map((p) => <option key={p} value={p}>{PLAN_LABEL[p]}</option>)}
      </select>
      {billing.plan === 'MONTHLY' && (
        <>
          <span className="tabular-nums text-muted">{billing.usedThisMonth} of</span>
          <input aria-label="Events per month" inputMode="numeric" value={quota} onChange={(e) => setQuota(e.target.value)}
            onBlur={() => Number(quota) !== billing.monthlyQuota && Number.isInteger(Number(quota)) && update({ monthlyQuota: Number(quota) })}
            className="h-8 w-14 rounded-md border border-line bg-white px-2 text-center text-sm tabular-nums" />
          <span className="text-muted">events this month</span>
        </>
      )}
      {isPack(billing.plan) && (
        <>
          <span className="tabular-nums text-muted">{billing.usedThisMonth} of {billing.monthlyQuota} this month · paid until</span>
          <input type="date" aria-label="Paid until" value={billing.planPaidUntil ?? ''}
            onChange={(e) => e.target.value && update({ planPaidUntil: e.target.value })}
            className={`h-8 rounded-md border bg-white px-2 text-sm ${billing.packActive ? 'border-line' : 'border-red-300 text-red-700'}`} />
          {!billing.packActive && <span className="text-xs font-medium text-red-700">ended</span>}
        </>
      )}
      {billing.plan !== 'UNLIMITED' && (
        <div className="flex w-full flex-wrap items-center gap-x-5 gap-y-2 border-t border-line pt-2" data-testid={`available-${billing.studioId}`}>
          <span className="text-xs font-medium">
            Available: <b className="tabular-nums">{billing.mainCredits + billing.quickCredits + packLeft}</b> events
            {packLeft > 0 && <span className="font-normal text-muted"> ({packLeft} left in pack this month)</span>}
            {' '}+ <b className="tabular-nums">{billing.clientCredits}</b> client
          </span>
          <CreditStepper label="Main" value={billing.mainCredits} busy={busy} onStep={(d) => void update({ addCredits: d })} />
          <CreditStepper label="Quick" value={billing.quickCredits} busy={busy} onStep={(d) => void update({ addQuickCredits: d })} />
          <CreditStepper label="Client" value={billing.clientCredits} busy={busy} onStep={(d) => void update({ addClientCredits: d })} />
        </div>
      )}
    </div>
  )
}
