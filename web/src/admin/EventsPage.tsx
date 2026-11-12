import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import type { BillingSummary, EventSummary, Purchase } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { useSession } from './auth'
import { BuyEventsDialog } from './BuyEventsDialog'
import { isPack, PLAN_LABEL, rupees } from './plans'
import { applyFilter, EMPTY_FILTER, filterFromParams, filterToParams, isFiltered, type EventFilter, type SortKey } from './eventFilters'
import { ErrorText, EventStatusBadge } from './ui'

const FIRST_STEPS = [
  { title: 'Create the event', body: 'Name and date. You get a private gallery address and its QR code.' },
  { title: 'Print the QR poster', body: 'In the event, open QR code, then Event & table cards. Put the event card in your studio board at the entrance and the table cards on the tables.' },
  { title: 'Connect a camera', body: 'In the event, under Cameras & uploaders, add an uploader and paste its code into the uploader app on the laptop.' },
]

const select = 'h-9 w-full rounded-lg border border-line bg-white px-2.5 text-sm'
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December']

/** Sort and filter bar; the choices live in the address, so a filtered list can be bookmarked or reloaded. */
function FilterBar({ events, filter, onChange, superAdmin }: { events: EventSummary[]; filter: EventFilter; onChange: (f: EventFilter) => void; superAdmin: boolean }) {
  const set = <K extends keyof EventFilter>(k: K, v: EventFilter[K]) => onChange({ ...filter, [k]: v })
  const years = [...new Set(events.map((e) => e.eventDate.slice(0, 4)))].sort().reverse()
  const studios = [...new Map(events.filter((e) => e.ownerName).map((e) => [e.ownerId, e.ownerName!])).entries()].sort((a, b) => a[1].localeCompare(b[1]))
  const labelled = (label: string, node: React.ReactNode) => (
    <label className="block min-w-0"><span className="mb-1 block text-xs text-muted">{label}</span>{node}</label>
  )
  return (
    <section aria-label="Sort and filter events" className="rounded-xl border border-line bg-white p-3 sm:p-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4">
        {labelled('Sort', <select className={select} value={filter.sort} onChange={(e) => set('sort', e.target.value as SortKey)}>
          <option value="newest">Newest first</option><option value="oldest">Oldest first</option><option value="name">Name A to Z</option></select>)}
        {labelled('Type', <select className={select} value={filter.type} onChange={(e) => set('type', e.target.value as EventFilter['type'])}>
          <option value="">All types</option><option value="MAIN">Main</option><option value="QUICK">Quick</option><option value="CLIENT">Client gallery</option></select>)}
        {labelled('Status', <select className={select} value={filter.status} onChange={(e) => set('status', e.target.value as EventFilter['status'])}>
          <option value="">All statuses</option>{['DRAFT', 'UPCOMING', 'LIVE', 'COMPLETED', 'ARCHIVED'].map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}</select>)}
        {superAdmin && labelled('Studio', <select className={select} value={filter.studio} onChange={(e) => set('studio', e.target.value)}>
          <option value="">All studios</option>{studios.map(([id, name]) => <option key={id} value={id}>{name}</option>)}</select>)}
      </div>
      <details className="mt-3 group" open={!!(filter.year || filter.month || filter.from || filter.to) || undefined}>
        <summary className="cursor-pointer text-sm font-medium text-stone-700 select-none">Date filters</summary>
        <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-4">
        {labelled('Year', <select className={select} value={filter.year} onChange={(e) => set('year', e.target.value)}>
          <option value="">Any year</option>{years.map((y) => <option key={y} value={y}>{y}</option>)}</select>)}
        {labelled('Month', <select className={select} value={filter.month} onChange={(e) => set('month', e.target.value)}>
          <option value="">Any month</option>{MONTHS.map((m, i) => <option key={m} value={String(i + 1).padStart(2, '0')}>{m}</option>)}</select>)}
        {labelled('From date', <input type="date" className={select} value={filter.from} max={filter.to || undefined} onChange={(e) => set('from', e.target.value)} />)}
        {labelled('To date', <input type="date" className={select} value={filter.to} min={filter.from || undefined} onChange={(e) => set('to', e.target.value)} />)}
        </div>
      </details>
      {isFiltered(filter) && (
        <button type="button" onClick={() => onChange({ ...EMPTY_FILTER, sort: filter.sort })} className="mt-3 text-sm text-muted underline underline-offset-2 hover:text-ink">
          Clear filters
        </button>
      )}
    </section>
  )
}

function formatDay(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' })
}

/** The studio's plan at a glance: pack events left, carried over, bought events, renewal, and what to buy. */
function PlanBar({ billing: b, onBuy }: { billing: BillingSummary; onBuy: (p: Purchase) => void }) {
  const pack = isPack(b.plan)
  const [now] = useState(() => Date.now())
  const daysLeft = b.planPaidUntil ? Math.ceil((Date.parse(`${b.planPaidUntil}T23:59:59`) - now) / 86_400_000) : null
  const renewSoon = pack && daysLeft !== null && daysLeft <= 5
  const owed = b.mainCredits < 0 || b.quickCredits < 0
  const bought = [
    b.mainCredits > 0 && `${b.mainCredits} Main`,
    b.quickCredits > 0 && `${b.quickCredits} Quick`,
    b.clientCredits > 0 && `${b.clientCredits} client galler${b.clientCredits === 1 ? 'y' : 'ies'}`,
  ].filter(Boolean).join(' + ')
  return (
    <div className="space-y-2">
      {pack && (renewSoon || !b.packActive) && (
        <div role="status" data-testid="renew-banner"
          className={`flex flex-wrap items-center justify-between gap-3 rounded-xl px-4 py-3 text-sm ${b.packActive ? 'bg-amber-50 text-amber-900' : 'bg-red-50 text-red-900'}`}>
          <p>
            {b.packActive
              ? <>Your {PLAN_LABEL[b.plan]} is paid until <b>{formatDay(b.planPaidUntil!)}</b>. Renew it to keep your monthly events.</>
              : <>Your {PLAN_LABEL[b.plan]} ended on <b>{formatDay(b.planPaidUntil!)}</b>. New events are charged one by one until you renew.</>}
          </p>
          <button type="button" onClick={() => onBuy({ item: 'PLAN', plan: b.plan, quantity: 1 })}
            className="inline-flex h-9 items-center rounded-lg bg-ink px-3.5 font-medium text-white">Renew pack · {rupees(b.monthlyPrice)}</button>
        </div>
      )}
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line bg-white px-4 py-3" data-testid="plan-usage">
        <p className="text-sm">
          {pack || b.plan === 'MONTHLY'
            ? <><span className="font-medium">{PLAN_LABEL[b.plan]}</span>{' · '}<b className="tabular-nums">{b.usedThisMonth} of {b.monthlyQuota}</b> used this month</>
            : <span className="font-medium">Per-event plan</span>}
          {b.carryOver > 0 && <span className="text-muted"> · {b.carryOver} carried over</span>}
          {pack && b.packActive && b.carryOver === 0 && b.usedThisMonth >= b.monthlyQuota && (
            <span className="text-muted" data-testid="extra-price"> · extra events {rupees(b.plan.startsWith('QUICK') ? b.quickUnitPrice : b.unitPrice)} each</span>
          )}
          {' · '}
          <span className={owed ? 'text-red-700' : 'text-muted'}>
            {owed ? 'payment not received for some events' : bought ? `${bought} bought` : 'no bought events'}
          </span>
          {pack && b.planPaidUntil && b.packActive && !renewSoon && <span className="text-muted"> · paid until {formatDay(b.planPaidUntil)}</span>}
        </p>
        <div className="flex gap-4 text-sm font-medium text-[#8a5a1c]">
          <button type="button" onClick={() => onBuy({ item: 'MAIN_EVENT', quantity: 1 })} className="hover:underline">Buy events</button>
          <button type="button" onClick={() => onBuy({ item: 'PLAN', plan: pack ? b.plan : 'MAIN_4', quantity: 1 })} className="hover:underline">
            {pack ? 'Renew or change pack' : 'Monthly packs'}
          </button>
        </div>
      </div>
    </div>
  )
}

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const superAdmin = useSession()?.role === 'SUPER_ADMIN'
  const [params, setParams] = useSearchParams()
  const filter = filterFromParams(params)
  const shown = events ? applyFilter(events, filter) : null

  const [billing, setBilling] = useState<BillingSummary | null>(null)
  const [buying, setBuying] = useState<Purchase | null>(null)
  const loadBilling = () => adminApi.billing().then(setBilling).catch(() => {})

  useEffect(() => {
    adminApi.events().then(setEvents).catch((e: Error) => setError(e.message))
    loadBilling()
  }, [])

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Events</h1>
        <Link to="/admin/events/new" className="inline-flex h-9 items-center rounded-lg bg-ink px-3.5 text-sm font-medium text-white hover:bg-stone-800">
          New event
        </Link>
      </div>
      {billing && !billing.free && <PlanBar billing={billing} onBuy={setBuying} />}
      {buying && billing && (
        <BuyEventsDialog billing={billing} purchase={buying} onClose={() => setBuying(null)} onPaid={() => {
          setBuying(null)
          loadBilling()
        }} />
      )}
      <ErrorText>{error}</ErrorText>
      {!events && !error && <ShutterLoader className="py-16" />}
      {events?.length === 0 && (
        <section className="rounded-xl border border-line bg-white p-6 sm:p-8" data-testid="first-event">
          <h2 className="text-lg font-semibold">Your first live gallery in three steps</h2>
          <p className="mt-1 text-sm text-muted">Guests scan a QR code at the venue and see photos seconds after they are taken.</p>
          <ol className="mt-6 grid gap-4 sm:grid-cols-3">
            {FIRST_STEPS.map((step, i) => (
              <li key={step.title} className="rounded-xl border border-line p-4">
                <span className="flex size-7 items-center justify-center rounded-full bg-stone-100 text-sm font-semibold tabular-nums">{i + 1}</span>
                <p className="mt-3 font-medium">{step.title}</p>
                <p className="mt-1 text-sm text-muted">{step.body}</p>
              </li>
            ))}
          </ol>
          <Link to="/admin/events/new" className="mt-6 inline-flex h-10 items-center rounded-lg bg-ink px-4 text-sm font-medium text-white hover:bg-stone-800">
            Create your first event
          </Link>
        </section>
      )}
      {events && events.length > 1 && <FilterBar events={events} filter={filter} superAdmin={superAdmin} onChange={(f) => setParams(filterToParams(f), { replace: true })} />}
      {events && events.length > 0 && shown?.length === 0 && (
        <p className="rounded-xl border border-line bg-white p-6 text-center text-sm text-muted" data-testid="no-match">No events match these filters.</p>
      )}
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {shown?.map((e) => (
          <li key={e.id}>
            <Link to={`/admin/events/${e.id}`} className="lift block rounded-xl border border-line bg-white p-5">
              <div className="flex items-start justify-between gap-3">
                <p className="font-medium">{e.name}</p>
                <EventStatusBadge status={e.status} />
              </div>
              <p className="mt-1 text-sm text-muted">
                {formatEventDate(e.eventDate)}
                {e.eventType === 'QUICK' && <span className="ml-2 rounded-full bg-stone-100 px-2 py-0.5 text-[11px] font-medium text-stone-600">Quick</span>}
                {e.eventType === 'CLIENT' && <span className="ml-2 rounded-full bg-[#f6ecd9] px-2 py-0.5 text-[11px] font-medium text-[#7a4f17]">Client gallery</span>}
              </p>
              {superAdmin && e.ownerName && <p className="mt-1 text-xs font-medium text-[#8a5a1c]">{e.ownerName}</p>}
              <p className="mt-4 text-sm">
                <span className="font-semibold tabular-nums">{formatCount(e.readyPhotos)}</span> <span className="text-muted">photos</span>
              </p>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  )
}
