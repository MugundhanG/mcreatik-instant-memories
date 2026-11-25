import { useEffect, useMemo, useState } from 'react'
import { Navigate } from 'react-router'
import { adminApi } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary, FaceUsage, FaceUsageDay, FaceUsageEvent } from '../lib/types'
import { useSession } from './auth'
import { facePackAddOn, rupees } from './plans'
import { Button, Card, ErrorText, Field, inputClass } from './ui'

/** AWS list prices and what a rupee is worth, editable because the real bill moves; kept in this browser only. */
interface Rates {
  /** US$ per image or search call (IndexFaces, SearchFacesByImage). */
  perCall: number
  /** US$ per stored face per month. */
  perFaceMonth: number
  /** ₹ per US$. */
  inr: number
  /** GST on the AWS bill, in percent. */
  gst: number
}

const DEFAULT_RATES: Rates = { perCall: 0.001, perFaceMonth: 0.00001, inr: 90, gst: 18 }
const RATES_KEY = 'mk-face-usage-rates'

function loadRates(): Rates {
  try {
    const saved = JSON.parse(localStorage.getItem(RATES_KEY) ?? 'null') as Partial<Rates> | null
    if (saved) {
      const ok = (n: unknown, d: number) => (typeof n === 'number' && Number.isFinite(n) && n >= 0 ? n : d)
      return { perCall: ok(saved.perCall, DEFAULT_RATES.perCall), perFaceMonth: ok(saved.perFaceMonth, DEFAULT_RATES.perFaceMonth), inr: ok(saved.inr, DEFAULT_RATES.inr), gst: ok(saved.gst, DEFAULT_RATES.gst) }
    }
  } catch {
    // Storage blocked or damaged: the defaults are fine.
  }
  return DEFAULT_RATES
}

/** Estimated ₹ for one event (or a sum): ((photos scanned + searches) x per call + faces x per face a month) x ₹ per US$ x (1 + GST). */
function estimateInr(u: Pick<FaceUsageEvent, 'photosScanned' | 'searches' | 'facesIndexed'>, r: Rates): number {
  return ((u.photosScanned + u.searches) * r.perCall + u.facesIndexed * r.perFaceMonth) * r.inr * (1 + r.gst / 100)
}

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`

type PeriodId = 'this' | 'last' | '90'
const PERIODS: { id: PeriodId; label: string }[] = [
  { id: 'this', label: 'This month' },
  { id: 'last', label: 'Last month' },
  { id: '90', label: 'Last 90 days' },
]

function periodRange(id: PeriodId, now = new Date()): [string, string] {
  const y = now.getFullYear()
  const m = now.getMonth()
  if (id === 'this') return [iso(new Date(y, m, 1)), iso(now)]
  if (id === 'last') return [iso(new Date(y, m - 1, 1)), iso(new Date(y, m, 0))]
  return [iso(new Date(y, m, now.getDate() - 90)), iso(now)]
}

const money = (n: number) => `₹${Math.round(n).toLocaleString('en-IN')}`
const avg = (n: number, count: number) => (count ? n / count : 0)

/** Profit (green) or loss (red) of charging `charge` where it costs `cost`. */
function Margin({ charge, cost }: { charge: number; cost: number }) {
  const diff = charge - cost
  return <span className={`font-medium tabular-nums ${diff >= 0 ? 'text-emerald-700' : 'text-red-700'}`}>{diff >= 0 ? `+${money(diff)} profit` : `−${money(-diff)} loss`}</span>
}

/** Per event type: what a Main or Quick event really used, against what the studio pays for it. */
function TypeAverages({ events, rates, billing }: { events: FaceUsageEvent[]; rates: Rates; billing: BillingSummary | null }) {
  const rows = (['MAIN', 'QUICK'] as const).map((type) => {
    const list = events.filter((e) => e.eventType === type)
    const sum = (f: (e: FaceUsageEvent) => number) => list.reduce((n, e) => n + f(e), 0)
    const cost = avg(sum((e) => estimateInr(e, rates)), list.length)
    const p = billing?.prices
    const oneOff = type === 'MAIN' ? p?.faceSearchMain : p?.faceSearchQuick
    // A 4-event pack's add-on shared over its events: what each event brings in.
    const packAddOn = p ? facePackAddOn(type === 'MAIN' ? 'MAIN_4' : 'QUICK_4', p) : undefined
    return { type, count: list.length, photos: avg(sum((e) => e.photosScanned), list.length), searches: avg(sum((e) => e.searches), list.length), cost, oneOff, packAddOn, share: packAddOn ? packAddOn / 4 : undefined }
  })
  return (
    <Card title="Average per event">
      <div className="grid gap-4 sm:grid-cols-2" data-testid="type-averages">
        {rows.map((r) => (
          <div key={r.type} className="rounded-xl border border-line p-4" data-testid={`avg-${r.type}`}>
            <p className="text-sm font-semibold">Per {r.type === 'MAIN' ? 'Main' : 'Quick'} event <span className="font-normal text-muted">· {r.count} event{r.count === 1 ? '' : 's'}</span></p>
            {r.count === 0 ? <p className="mt-2 text-sm text-muted">No events of this type in this period.</p> : (
              <>
                <dl className="mt-2 grid grid-cols-3 gap-2 text-sm">
                  <div><dt className="text-xs text-muted">Photos scanned</dt><dd className="font-medium tabular-nums">{formatCount(Math.round(r.photos))}</dd></div>
                  <div><dt className="text-xs text-muted">Searches</dt><dd className="font-medium tabular-nums">{formatCount(Math.round(r.searches))}</dd></div>
                  <div><dt className="text-xs text-muted">Estimated cost</dt><dd className="font-medium tabular-nums">{money(r.cost)}</dd></div>
                </dl>
                <ul className="mt-3 space-y-1.5 text-sm">
                  {r.oneOff !== undefined && (
                    <li className="flex flex-wrap items-baseline justify-between gap-x-3"><span className="text-muted">We charge {rupees(r.oneOff)} one by one</span><Margin charge={r.oneOff} cost={r.cost} /></li>
                  )}
                  {r.share !== undefined && r.packAddOn !== undefined && (
                    <li className="flex flex-wrap items-baseline justify-between gap-x-3"><span className="text-muted">With a pack: {rupees(r.packAddOn)} ÷ 4 = {rupees(r.share)} per event</span><Margin charge={r.share} cost={r.cost} /></li>
                  )}
                </ul>
              </>
            )}
          </div>
        ))}
      </div>
    </Card>
  )
}

/** Searches and photos scanned per day as two bars; hover (or focus) a day for its numbers. */
function DailyChart({ days }: { days: FaceUsageDay[] }) {
  const [active, setActive] = useState<FaceUsageDay | null>(null)
  const max = Math.max(1, ...days.map((d) => Math.max(d.searches, d.photosScanned)))
  return (
    <Card title="Per day">
      <div className="flex h-36 items-end gap-px overflow-x-auto" role="img" aria-label="Searches and photos scanned per day" data-testid="daily-chart">
        {days.map((d) => (
          <div key={d.day} tabIndex={0} onMouseEnter={() => setActive(d)} onMouseLeave={() => setActive(null)} onFocus={() => setActive(d)} onBlur={() => setActive(null)}
            title={`${d.day}: ${formatCount(d.photosScanned)} photos scanned, ${formatCount(d.searches)} searches`}
            className="flex h-full min-w-1.5 flex-1 items-end justify-center gap-px rounded-sm hover:bg-stone-100">
            <div className="w-1/2 rounded-t-sm bg-[#b37a2c]" style={{ height: `${(d.photosScanned / max) * 100}%` }} />
            <div className="w-1/2 rounded-t-sm bg-sky-600" style={{ height: `${(d.searches / max) * 100}%` }} />
          </div>
        ))}
      </div>
      <p className="mt-2 min-h-5 text-xs text-muted" aria-live="polite">
        {active ? <><b className="text-ink">{active.day}</b> · {formatCount(active.photosScanned)} photos scanned · {formatCount(active.searches)} searches · {formatCount(active.facesIndexed)} faces</> : 'Point at a day for its numbers.'}
      </p>
      <p className="mt-1 flex gap-4 text-xs text-muted">
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-[#b37a2c]" />Photos scanned</span>
        <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-sm bg-sky-600" />Searches</span>
      </p>
    </Card>
  )
}

/** McreatiK only: what face search really uses on AWS Rekognition, event by event, before building our own engine. */
export default function FaceUsagePage() {
  const session = useSession()
  const [period, setPeriod] = useState<PeriodId>('this')
  const [rates, setRates] = useState<Rates>(loadRates)
  const [text, setText] = useState<Record<keyof Rates, string>>(() => ({ perCall: String(rates.perCall), perFaceMonth: String(rates.perFaceMonth), inr: String(rates.inr), gst: String(rates.gst) }))
  const [usage, setUsage] = useState<FaceUsage | null>(null)
  const [billing, setBilling] = useState<BillingSummary | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [desc, setDesc] = useState(true)
  const superAdmin = session?.role === 'SUPER_ADMIN'

  useEffect(() => {
    if (!superAdmin) return
    let active = true
    const [from, to] = periodRange(period)
    adminApi.faceUsage(from, to).then((u) => active && setUsage(u), (e: Error) => active && setError(e.message || 'Could not load the usage'))
    return () => {
      active = false
    }
  }, [period, superAdmin])
  useEffect(() => {
    if (superAdmin) adminApi.billing().then(setBilling).catch(() => {})
  }, [superAdmin])

  // The old numbers never sit under the next period's label while it loads.
  const pick = (id: PeriodId) => {
    setUsage(null)
    setError(null)
    setPeriod(id)
  }
  // The inputs keep what was typed ("0." on the way to "0.002"); the estimate uses the number it makes.
  const setRate = (k: keyof Rates) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const n = Number(e.target.value)
    setText({ ...text, [k]: e.target.value })
    const next = { ...rates, [k]: Number.isFinite(n) && n >= 0 ? n : 0 }
    setRates(next)
    try {
      localStorage.setItem(RATES_KEY, JSON.stringify(next))
    } catch {
      // Not saved (private window); the page still works for now.
    }
  }

  const rows = useMemo(() => (usage?.events ?? []).map((e) => ({ e, cost: estimateInr(e, rates) })).sort((a, b) => (desc ? b.cost - a.cost : a.cost - b.cost)), [usage, rates, desc])
  const totals = useMemo(() => {
    const events = usage?.events ?? []
    const sum = (f: (e: FaceUsageEvent) => number) => events.reduce((n, e) => n + f(e), 0)
    return { events: events.length, photos: sum((e) => e.photosScanned), searches: sum((e) => e.searches), faces: sum((e) => e.facesIndexed), cost: estimateInr({ photosScanned: sum((e) => e.photosScanned), searches: sum((e) => e.searches), facesIndexed: sum((e) => e.facesIndexed) }, rates) }
  }, [usage, rates])

  if (!superAdmin) return <Navigate to="/admin" replace />

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Face search usage</h1>
        <p className="text-sm text-muted">What “Find my photos” really uses on AWS, event by event.</p>
      </div>

      <div className="flex flex-wrap gap-2" role="group" aria-label="Period">
        {PERIODS.map((p) => (
          <Button key={p.id} variant={period === p.id ? 'primary' : 'secondary'} aria-pressed={period === p.id} onClick={() => pick(p.id)}>{p.label}</Button>
        ))}
      </div>

      <Card title="Rates">
        <div className="grid gap-4 sm:grid-cols-4">
          <Field label="US$ per image or search call"><input className={inputClass} inputMode="decimal" aria-label="US$ per image or search call" value={text.perCall} onChange={setRate('perCall')} /></Field>
          <Field label="US$ per face stored per month"><input className={inputClass} inputMode="decimal" aria-label="US$ per face stored per month" value={text.perFaceMonth} onChange={setRate('perFaceMonth')} /></Field>
          <Field label="₹ per US$"><input className={inputClass} inputMode="decimal" aria-label="₹ per US$" value={text.inr} onChange={setRate('inr')} /></Field>
          <Field label="GST (%)"><input className={inputClass} inputMode="decimal" aria-label="GST (%)" value={text.gst} onChange={setRate('gst')} /></Field>
        </div>
        <p className="mt-3 text-xs text-muted" data-testid="estimate-note">
          Estimated cost per event = ((photos scanned + searches) × per call + faces × per face a month) × ₹ per US$ × (1 + GST).
          Estimate from AWS list prices; your AWS bill is the real figure. Face storage is counted as one month.
        </p>
      </Card>

      <ErrorText>{error}</ErrorText>
      {!usage && !error && <p className="text-sm text-muted">Loading…</p>}

      {usage && usage.events.length === 0 && (
        <p className="rounded-xl border border-line bg-white p-6 text-center text-sm text-muted" data-testid="face-usage-empty">No face search usage in this period yet.</p>
      )}

      {usage && usage.events.length > 0 && (
        <>
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-5" data-testid="face-usage-totals">
            {[['Events', formatCount(totals.events)], ['Photos scanned', formatCount(totals.photos)], ['Searches', formatCount(totals.searches)], ['Faces', formatCount(totals.faces)], ['Estimated cost', money(totals.cost)]].map(([k, v]) => (
              <div key={k} className="rounded-xl border border-line bg-white p-4">
                <dt className="text-xs text-muted">{k}</dt>
                <dd className="mt-1 text-xl font-semibold tabular-nums" data-testid={`total-${k}`}>{v}</dd>
              </div>
            ))}
          </dl>

          <TypeAverages events={usage.events} rates={rates} billing={billing} />
          <DailyChart days={usage.days} />

          <Card title="Events" bodyClassName="!p-0">
            <div className="overflow-x-auto">
              <table className="w-full text-sm" data-testid="face-usage-table">
                <thead className="bg-stone-50 text-left text-xs text-muted">
                  <tr>
                    <th className="px-4 py-2 font-medium">Event</th>
                    <th className="px-4 py-2 font-medium">Studio</th>
                    <th className="px-4 py-2 font-medium">Type</th>
                    <th className="px-4 py-2 font-medium">Date</th>
                    <th className="px-4 py-2 text-right font-medium">Photos scanned</th>
                    <th className="px-4 py-2 text-right font-medium">Searches</th>
                    <th className="px-4 py-2 text-right font-medium">Faces</th>
                    <th className="px-4 py-2 text-right font-medium" aria-sort={desc ? 'descending' : 'ascending'}>
                      <button type="button" onClick={() => setDesc((d) => !d)} className="font-medium hover:text-ink">Est. ₹ {desc ? '↓' : '↑'}</button>
                    </th>
                    <th className="px-4 py-2 font-medium">Paid by studio</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(({ e, cost }) => (
                    <tr key={e.eventId} className="border-t border-line">
                      <td className="px-4 py-2.5">{e.eventName ?? <span className="text-muted">Deleted event</span>}</td>
                      <td className="px-4 py-2.5">{e.studioName ?? '–'}</td>
                      <td className="px-4 py-2.5">{e.eventType === 'MAIN' ? 'Main' : e.eventType === 'QUICK' ? 'Quick' : '–'}</td>
                      <td className="px-4 py-2.5 whitespace-nowrap">{e.eventDate ?? '–'}</td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{formatCount(e.photosScanned)}</td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{formatCount(e.searches)}</td>
                      <td className="px-4 py-2.5 text-right tabular-nums">{formatCount(e.facesIndexed)}</td>
                      <td className="px-4 py-2.5 text-right font-medium tabular-nums">{money(cost)}</td>
                      <td className="px-4 py-2.5">{e.paid ? 'Yes' : <span className="text-amber-800">No · free trial</span>}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Card>
        </>
      )}
    </div>
  )
}
