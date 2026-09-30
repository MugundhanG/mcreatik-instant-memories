import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import type { EventSummary } from '../lib/types'
import { ErrorText, EventStatusBadge } from './ui'

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    adminApi.events().then(setEvents).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">Events</h1>
        <Link to="/admin/events/new" className="inline-flex h-9 items-center rounded-lg bg-ink px-3.5 text-sm font-medium text-white hover:bg-stone-800">
          New event
        </Link>
      </div>
      <ErrorText>{error}</ErrorText>
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
