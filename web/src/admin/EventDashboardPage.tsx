import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import { formatBytes, formatCount, formatEventDate, timeAgo } from '../lib/format'
import type { EventDetail, EventStats, EventStatus } from '../lib/types'
import { PhotosPanel } from './PhotosPanel'
import { QrPanel } from './QrPanel'
import { Button, Card, ErrorText, EventStatusBadge, Field, inputClass, Stat } from './ui'
import { UploadersPanel } from './UploadersPanel'

const STATUSES: EventStatus[] = ['DRAFT', 'UPCOMING', 'LIVE', 'COMPLETED', 'ARCHIVED']
const POLL_MS = 5_000

export default function EventDashboardPage() {
  const { eventId = '' } = useParams()
  const navigate = useNavigate()
  const [event, setEvent] = useState<EventDetail | null>(null)
  const [stats, setStats] = useState<EventStats | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadStats = useCallback(
    () =>
      adminApi
        .stats(eventId)
        .then(setStats)
        .catch((e: Error) => setError(e.message || 'Could not load statistics')),
    [eventId],
  )

  useEffect(() => {
    adminApi.event(eventId).then(setEvent).catch((e: Error) => setError(e.message))
    const load = () => adminApi.stats(eventId).then(setStats).catch(() => {})
    load()
    const timer = setInterval(load, POLL_MS)
    return () => clearInterval(timer)
  }, [eventId])

  const update = async (input: Parameters<typeof adminApi.updateEvent>[1]) => {
    try {
      setEvent(await adminApi.updateEvent(eventId, input))
      setError(null)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Update failed')
    }
  }

  const deleteEvent = async () => {
    if (!event) return
    const typed = prompt(`This permanently deletes “${event.name}”, all photos and all files.\nType the event name to confirm:`)
    if (typed !== event.name) return
    await adminApi.deleteEvent(event.id)
    navigate('/admin', { replace: true })
  }

  if (!event) return <ErrorText>{error}</ErrorText>

  return (
    <div className="space-y-6">
      <Link to="/admin" className="text-sm text-muted hover:text-ink">← Events</Link>

      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-semibold">{event.name}</h1>
            <EventStatusBadge status={event.status} />
          </div>
          <p className="mt-1 text-sm text-muted">
            {formatEventDate(event.eventDate)} ·{' '}
            <a href={event.galleryUrl} target="_blank" rel="noreferrer" className="underline decoration-line underline-offset-4 hover:text-ink">
              {event.galleryUrl.replace(/^https?:\/\//, '')}
            </a>
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm">
          <span className="text-muted">Status</span>
          <select className={`${inputClass} w-40`} value={event.status} onChange={(e) => update({ status: e.target.value as EventStatus })}>
            {STATUSES.map((s) => <option key={s} value={s}>{s.charAt(0) + s.slice(1).toLowerCase()}</option>)}
          </select>
        </label>
      </div>
      <ErrorText>{error}</ErrorText>

      {stats && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6" data-testid="event-stats">
          <Stat label="Total photos" value={formatCount(stats.totalPhotos)} />
          <Stat label="In gallery" value={formatCount(stats.readyPhotos)} />
          <Stat label="Processing" value={formatCount(stats.inProgressPhotos)} />
          <Stat label="Failed" value={formatCount(stats.failedPhotos)} tone={stats.failedPhotos > 0 ? 'danger' : undefined} />
          <Stat label="Active uploaders" value={`${stats.activeUploaders} / ${stats.uploaders.length}`} />
          <Stat label="Storage used" value={formatBytes(stats.storageBytes)} />
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <UploadersPanel eventId={event.id} uploaders={stats?.uploaders ?? []} onChange={loadStats} />
          {stats?.latestPhoto && (
            <Card title="Latest photo">
              <div className="flex items-center gap-4">
                {stats.latestPhoto.thumbnailUrl && (
                  <img src={stats.latestPhoto.thumbnailUrl} alt="" className="size-20 rounded-lg object-cover" />
                )}
                <div className="text-sm">
                  <p className="font-medium">{stats.latestPhoto.originalFileName}</p>
                  <p className="text-muted">In gallery {timeAgo(stats.latestPhoto.readyAt)}</p>
                </div>
              </div>
            </Card>
          )}
        </div>
        <QrPanel eventId={event.id} eventSlug={event.slug} galleryUrl={event.galleryUrl} />
      </div>

      <PhotosPanel eventId={event.id} coverPhotoId={event.coverPhotoId} refreshKey={(stats?.readyPhotos ?? 0) + (stats?.failedPhotos ?? 0)} onCoverChange={(id) => update({ coverPhotoId: id })} />

      <Card title="Privacy & data">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <Field label="Delete everything after" hint="Photos and files are permanently removed on this date. Leave empty to keep.">
            <input
              type="date"
              className={`${inputClass} w-48`}
              value={event.retentionUntil ?? ''}
              onChange={(e) => update(e.target.value ? { retentionUntil: e.target.value } : { clearRetention: true })}
            />
          </Field>
          <Button variant="danger" onClick={deleteEvent}>Delete event now</Button>
        </div>
      </Card>
    </div>
  )
}
