import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { IS_DEMO } from '../demo/isDemo'
import { adminApi } from '../lib/api'
import { formatEventDate } from '../lib/format'
import type { EventDetail } from '../lib/types'
import { QrCode } from './QrPanel'

/** A4 table-card poster: open, press Print. */
export default function PrintQrPage() {
  const { eventId = '' } = useParams()
  const [event, setEvent] = useState<EventDetail | null>(null)

  useEffect(() => {
    adminApi.event(eventId).then(setEvent)
  }, [eventId])

  if (!event) return null

  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-white px-8 py-12 text-center">
      <p className="text-xs tracking-[0.35em] text-muted uppercase">McreatiK Studios</p>
      <h1 className="mt-6 font-display text-5xl leading-tight font-medium">{event.name}</h1>
      <p className="mt-2 text-muted">{formatEventDate(event.eventDate)}</p>
      <div className="mt-10 w-72 sm:w-80">
        <QrCode url={event.galleryUrl} />
      </div>
      <p className="mt-8 font-display text-3xl">Scan to see the photos live</p>
      <p className="mt-2 text-sm text-muted">No app or sign-up needed · {event.galleryUrl.replace(/^https?:\/\//, '')}</p>
      <div className="no-print mt-10 flex items-center gap-4">
        <Link to={`/admin/events/${event.id}`} className="text-sm text-muted hover:text-ink">← Back to event</Link>
        {!IS_DEMO && (
          <button type="button" onClick={() => window.print()} className="rounded-full bg-ink px-6 py-2.5 text-sm font-medium text-white">
            Print
          </button>
        )}
      </div>
    </div>
  )
}
