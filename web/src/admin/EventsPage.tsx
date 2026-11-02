import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import type { BillingSummary, EventSummary } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { useSession } from './auth'
import { BuyEventsDialog } from './BuyEventsDialog'
import { ErrorText, EventStatusBadge } from './ui'

const FIRST_STEPS = [
  { title: 'Create the event', body: 'Name and date. You get a private gallery address and its QR code.' },
  { title: 'Print the QR poster', body: 'In the event, press QR code, then Print poster. Place it at the entrance and on the tables.' },
  { title: 'Connect a camera', body: 'In the event, under Cameras & uploaders, add an uploader and paste its code into the uploader app on the laptop.' },
]

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const superAdmin = useSession()?.role === 'SUPER_ADMIN'

  const [billing, setBilling] = useState<BillingSummary | null>(null)
  const [buying, setBuying] = useState(false)
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
      {billing && !billing.free && (
        <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line bg-white px-4 py-3" data-testid="plan-usage">
          <p className="text-sm">
            {billing.plan === 'MONTHLY'
              ? <><b className="tabular-nums">{billing.usedThisMonth} of {billing.monthlyQuota}</b> events used this month</>
              : <>Per-event plan</>}
            {' · '}
            <span className={billing.credits < 0 ? 'text-red-700' : 'text-muted'}>
              {billing.credits < 0 ? `${-billing.credits} event${billing.credits === -1 ? '' : 's'} owed (payment not received)` : `${billing.credits} extra event${billing.credits === 1 ? '' : 's'} available`}
            </span>
          </p>
          <button type="button" onClick={() => setBuying(true)} className="text-sm font-medium text-[#8a5a1c] hover:underline">Buy events</button>
        </div>
      )}
      {buying && billing && (
        <BuyEventsDialog billing={billing} onClose={() => setBuying(false)} onPaid={() => {
          setBuying(false)
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
              <p className="mt-1 text-sm text-muted">{formatEventDate(e.eventDate)}</p>
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
