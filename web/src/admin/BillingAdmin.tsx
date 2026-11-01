import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { timeAgo } from '../lib/format'
import type { BillingSettings, EventPayment, Plan, StudioBilling } from '../lib/types'
import { PLAN_LABEL } from './plans'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { useConfirm } from './useConfirm'

const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

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
      body: `Only if transaction ${p.utr} is not in your bank statement. Their ${p.events} event${p.events === 1 ? '' : 's'} are taken back.`,
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
              <p className="text-xs text-muted">{p.events} event{p.events === 1 ? '' : 's'} × {rupees(p.unitPrice)} · {timeAgo(p.createdAt)}</p>
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
        <Field label="Per-event plan: price per event (₹)">
          <input className={inputClass} inputMode="numeric" value={s.perEventPrice} onChange={num('perEventPrice')} />
        </Field>
        <Field label="Monthly package: extra event price (₹)">
          <input className={inputClass} inputMode="numeric" value={s.extraEventPrice} onChange={num('extraEventPrice')} />
        </Field>
        <Field label="Monthly package price (₹/month)" hint="Collected by you; shown for reference">
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

/** One studio's plan, monthly allowance, usage and event balance; McreatiK can give free events (e.g. a trial). */
export function StudioPlan({ billing, onChange }: { billing: StudioBilling; onChange: () => void }) {
  const [quota, setQuota] = useState(String(billing.monthlyQuota))
  const update = async (input: { plan?: Plan; monthlyQuota?: number; addCredits?: number }) => {
    await adminApi.updateStudioBilling(billing.studioId, input)
    onChange()
  }
  const give = () => {
    const n = Number(window.prompt('How many free events to give? (use a minus number to take back)', '1'))
    if (Number.isInteger(n) && n !== 0) void update({ addCredits: n })
  }
  return (
    <div className="flex w-full flex-wrap items-center gap-2 rounded-lg bg-stone-50 px-3 py-2 text-sm" data-testid={`plan-${billing.studioId}`}>
      <select aria-label="Plan" value={billing.plan} onChange={(e) => update({ plan: e.target.value as Plan })}
        className="h-8 rounded-md border border-line bg-white px-2 text-sm">
        {(Object.keys(PLAN_LABEL) as Plan[]).map((p) => <option key={p} value={p}>{PLAN_LABEL[p]}</option>)}
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
      {billing.plan !== 'UNLIMITED' && (
        <>
          <span className={`tabular-nums ${billing.credits < 0 ? 'font-medium text-red-700' : 'text-muted'}`}>· {billing.credits} extra event{billing.credits === 1 ? '' : 's'}</span>
          <button type="button" onClick={give} className="ml-auto text-xs font-medium text-[#8a5a1c] hover:underline">Give / take events</button>
        </>
      )}
    </div>
  )
}
