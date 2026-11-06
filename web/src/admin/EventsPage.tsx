import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import type { BillingSummary, EventSummary, Purchase } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { useSession } from './auth'
import { BuyEventsDialog } from './BuyEventsDialog'
import { isPack, PLAN_LABEL, rupees } from './plans'
import { ErrorText, EventStatusBadge } from './ui'

const FIRST_STEPS = [
  { title: 'Create the event', body: 'Name and date. You get a private gallery address and its QR code.' },
  { title: 'Print the QR poster', body: 'In the event, open QR code, then Event & table cards. Put the event card in your studio board at the entrance and the table cards on the tables.' },
  { title: 'Connect a camera', body: 'In the event, under Cameras & uploaders, add an uploader and paste its code into the uploader app on the laptop.' },
]

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
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {events?.map((e) => (
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
