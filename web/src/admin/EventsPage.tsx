import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import type { BillingSummary, EventSummary } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { useSession } from './auth'
import { BuyEventsDialog } from './BuyEventsDialog'
import { ErrorText, EventStatusBadge } from './ui'

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
        <div className="rounded-xl border border-dashed border-line bg-white px-6 py-16 text-center">
          <p className="font-medium">No events yet</p>
          <p className="mt-1 text-sm text-muted">Create an event to get its QR code and connect cameras.</p>
        </div>
      )}
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {events?.map((e) => (
          <li key={e.id}>
            <Link to={`/admin/events/${e.id}`} className="block rounded-xl border border-line bg-white p-5 transition-shadow hover:shadow-sm">
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
