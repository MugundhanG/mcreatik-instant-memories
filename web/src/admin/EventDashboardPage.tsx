import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import { formatBytes, formatCount, formatEventDate, timeAgo } from '../lib/format'
import type { BillingSummary, Category, EventClient, EventDetail, EventStats, EventStatus, Gallery } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { AnalyticsPanel } from './AnalyticsPanel'
import { CategoriesCard } from './CategoriesCard'
import { ClientsCard } from './ClientsCard'
import { GalleryAccessCard, GalleryAccessNotice } from './GalleryAccessCard'
import { useSession } from './auth'
import { PhotosPanel } from './PhotosPanel'
import { QrPanel } from './QrPanel'
import { UpgradeBanner } from './UpgradeBanner'
import { GalleryStorageCard } from './GalleryStorageCard'
import { EventStatusMenu } from './EventStatusMenu'
import { Button, Card, ErrorText, Field, inputClass, Tabs } from './ui'
import { useConfirm } from './useConfirm'
import { UploadersPanel } from './UploadersPanel'
import { UploadPhotosPanel } from './UploadPhotosPanel'

const POLL_MS = 5_000

type Tab = 'overview' | 'photos' | 'guests' | 'couple' | 'settings'
const TABS: Tab[] = ['overview', 'photos', 'guests', 'couple', 'settings']

/**
 * One line of numbers instead of a wall of tiles. Problems (photos still processing, or failed) appear only when
 * there are some, as a coloured chip, so the eye goes straight to them.
 */
function StatStrip({ stats, clientOnly = false }: { stats: EventStats; clientOnly?: boolean }) {
  const items = [
    [formatCount(stats.totalPhotos), 'photos'],
    [formatCount(stats.readyPhotos - stats.hiddenPhotos), 'in gallery'],
    ...(clientOnly ? [] : [[`${stats.activeUploaders} / ${stats.uploaders.length}`, 'cameras online']]),
    [formatBytes(stats.storageBytes), 'stored'],
  ]
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2 rounded-xl border border-line bg-white px-5 py-3.5 text-sm" data-testid="event-stats">
      {items.map(([value, label]) => (
        <span key={label} className="whitespace-nowrap">
          <b className="text-base font-semibold tabular-nums">{value}</b> <span className="text-muted">{label}</span>
        </span>
      ))}
      {stats.inProgressPhotos > 0 && (
        <span className="rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-800">{formatCount(stats.inProgressPhotos)} processing</span>
      )}
      {stats.failedPhotos > 0 && (
        <span className="rounded-full bg-red-50 px-2.5 py-0.5 text-xs font-medium text-red-700">{formatCount(stats.failedPhotos)} failed</span>
      )}
    </div>
  )
}

export default function EventDashboardPage() {
  const { eventId = '' } = useParams()
  const navigate = useNavigate()
  const [event, setEvent] = useState<EventDetail | null>(null)
  const [billing, setBilling] = useState<BillingSummary | null>(null)
  const [stats, setStats] = useState<EventStats | null>(null)
  const [error, setError] = useState<string | null>(null)
  const superAdmin = useSession()?.role === 'SUPER_ADMIN'
  const [confirm, confirmDialog] = useConfirm()
  const [params, setParams] = useSearchParams()
  const tab: Tab = TABS.includes(params.get('tab') as Tab) ? (params.get('tab') as Tab) : 'overview'
  const openTab = useCallback((t: Tab) => setParams(t === 'overview' ? {} : { tab: t }, { replace: true }), [setParams])
  const [copied, setCopied] = useState(false)

  // Each gallery (guest, client) has its own categories.
  const [categories, setCategories] = useState<Record<Gallery, Category[]>>({ GUEST: [], CLIENT: [] })
  const [galleryTab, setGalleryTab] = useState<Gallery>('GUEST')
  const [eventClients, setEventClients] = useState<EventClient[] | null>(null)
  const loadCategories = useCallback(
    () =>
      Promise.all([adminApi.categories(eventId, 'GUEST'), adminApi.categories(eventId, 'CLIENT')])
        .then(([GUEST, CLIENT]) => setCategories({ GUEST, CLIENT }))
        .catch(() => {}),
    [eventId],
  )
  useEffect(() => {
    loadCategories()
  }, [loadCategories])

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
    adminApi.billing().then(setBilling).catch(() => {})
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

  // Archiving hides the gallery and stops the cameras, so it is never one click away.
  const changeStatus = async (status: EventStatus) => {
    if (status === 'ARCHIVED') {
      const ok = await confirm({
        title: 'Archive this event?',
        body: 'Guests can no longer open the gallery and cameras stop uploading. You can change the status back at any time.',
        confirmLabel: 'Archive event',
      })
      if (!ok) return
    }
    update({ status })
  }

  const copyLink = async () => {
    if (!event) return
    try {
      await navigator.clipboard.writeText(event.galleryUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard refused: the address is shown on the page to copy by hand.
    }
  }

  const deleteEvent = async () => {
    if (!event) return
    const ok = await confirm({
      title: 'Delete this event permanently?',
      body: `All photos, files, uploaders and the guest gallery of “${event.name}” are deleted. This cannot be undone.`,
      confirmLabel: 'Delete event',
      danger: true,
      requireText: event.name,
    })
    if (!ok) return
    await adminApi.deleteEvent(event.id)
    navigate('/admin', { replace: true })
  }

  if (!event) return error ? <ErrorText>{error}</ErrorText> : <ShutterLoader label="Loading event" className="py-24" />

  const client = eventClients?.[0]
  const clientOnly = event.eventType === 'CLIENT'
  const galleryOff = event.liveGalleryEnabled === false || event.clientGalleryEnabled === false
  // A section fades in each time it is shown (CSS animations restart when an element stops being hidden).
  // A client gallery has no guests: an old ?tab=guests link shows the Overview.
  const shown: Tab = clientOnly && tab === 'guests' ? 'overview' : tab
  const activeGallery: Gallery = clientOnly ? 'CLIENT' : galleryTab
  const galleryPanel = (id: Gallery) => ({ id: `panel-${id}`, role: 'tabpanel', 'aria-labelledby': `tab-${id}`, hidden: activeGallery !== id })
  const panel = (id: Tab) => ({ id: `panel-${id}`, role: 'tabpanel', 'aria-labelledby': `tab-${id}`, hidden: shown !== id })

  return (
    <div className="space-y-5">
      {confirmDialog}
      <Link to="/admin" className="text-sm text-muted hover:text-ink">← Events</Link>

      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold tracking-tight">{event.name}</h1>
            <EventStatusMenu status={event.status} onChange={changeStatus} />
          </div>
          <p className="mt-1 text-sm text-muted">
            {superAdmin && event.ownerName && <span className="font-medium text-[#8a5a1c]">{event.ownerName} · </span>}
            {formatEventDate(event.eventDate)}{clientOnly ? ' · Client gallery' : ' · '}
            {!clientOnly && <Link to={`/e/${event.slug}`} className="inline-block max-w-full truncate align-bottom underline decoration-line underline-offset-4 hover:text-ink">
              {event.galleryUrl.replace(/^https?:\/\//, '')}
            </Link>}
          </p>
        </div>
        {!clientOnly && <div className="flex gap-2">
          <Button onClick={copyLink}>{copied ? 'Copied ✓' : 'Copy gallery link'}</Button>
          <Button onClick={() => { openTab('overview'); document.getElementById('qr')?.scrollIntoView({ block: 'center' }) }}>QR code</Button>
        </div>}
      </header>

      <ErrorText>{error}</ErrorText>
      <UpgradeBanner event={event} onUpgraded={() => adminApi.event(event.id).then(setEvent).catch(() => {})} />
      {superAdmin ? (
        galleryOff && (
          <div role="status" className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
            <span><b>{event.liveGalleryEnabled === false ? 'Guest gallery' : 'Client gallery'}{event.liveGalleryEnabled === false && event.clientGalleryEnabled === false ? ' and client gallery' : ''} turned off.</b> Nobody can open it until it is turned back on.</span>
            <button type="button" onClick={() => openTab('settings')} className="font-medium underline underline-offset-4">Gallery access</button>
          </div>
        )
      ) : (
        <GalleryAccessNotice event={event} />
      )}

      <div className="sticky top-0 z-20 -mx-4 bg-stone-50/95 px-4 backdrop-blur sm:-mx-6 sm:px-6">
        <Tabs<Tab>
          label="Event sections"
          current={shown}
          onChange={openTab}
          tabs={[
            { id: 'overview', label: 'Overview' },
            { id: 'photos', label: 'Photos', badge: stats ? formatCount(stats.totalPhotos) : undefined },
            ...(clientOnly ? [] : [{ id: 'guests' as Tab, label: 'Guests' }]),
            { id: 'couple', label: 'Couple', badge: client ? '✓' : undefined },
            { id: 'settings', label: 'Settings' },
          ]}
        />
      </div>

      {/* Every section stays mounted (only hidden), so an upload or a ZIP keeps going while you look elsewhere. */}
      <section {...panel('overview')} className="anim-fade-in space-y-6">
        {stats && <StatStrip stats={stats} clientOnly={clientOnly} />}
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="space-y-6 lg:col-span-2">
            <GalleryStorageCard event={event} billing={billing} bytes={stats?.storageBytes ?? event.storageBytes ?? 0} onChanged={() => {
              adminApi.event(event.id).then(setEvent).catch(() => {})
              adminApi.billing().then(setBilling).catch(() => {})
              loadStats()
            }} />
            {clientOnly ? (
              <Card title="Client gallery">
                <ol className="list-decimal space-y-1.5 pl-5 text-sm text-stone-700" data-testid="client-steps">
                  <li>Add storage for the photos (above).</li>
                  <li>Upload the photos in the <button type="button" onClick={() => openTab('photos')} className="font-medium underline underline-offset-4">Photos</button> tab, with full-quality originals.</li>
                  <li>Create the couple's login in the <button type="button" onClick={() => openTab('couple')} className="font-medium underline underline-offset-4">Couple</button> tab and send it with the couple guide.</li>
                </ol>
                <p className="mt-3 text-xs text-muted">There's no guest QR gallery for a client gallery: only the couple can open it.</p>
              </Card>
            ) : (
              <UploadersPanel eventId={event.id} uploaders={stats?.uploaders ?? []} onChange={loadStats} />
            )}
            {stats?.latestPhoto && (
              <Card title="Latest photo" action={<button type="button" onClick={() => openTab('photos')} className="text-sm text-muted hover:text-ink">All photos →</button>}>
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
            <button type="button" onClick={() => openTab('couple')} data-testid="client-login-chip"
              className="flex w-full items-center justify-between gap-3 rounded-xl border border-line bg-white px-5 py-3.5 text-left text-sm hover:bg-stone-50">
              {client ? (
                <span><span className="text-muted">Couple login:</span> <b className="font-medium">{client.name}</b> · {client.email}
                  <span className="text-muted">{client.disabled ? ' · turned off' : client.lastLoginAt ? ' · active' : ' · not logged in yet'}</span></span>
              ) : (
                <span className="text-muted">No couple login yet. Create one so they can pick album photos and download originals.</span>
              )}
              <span aria-hidden="true" className="text-muted">→</span>
            </button>
          </div>
          {!clientOnly && <div id="qr"><QrPanel eventId={event.id} eventSlug={event.slug} galleryUrl={event.galleryUrl} /></div>}
        </div>
      </section>

      <section {...panel('photos')} className="anim-fade-in space-y-6">
        {/* Both galleries stay mounted (only hidden), so an upload keeps going while you look at the other one. */}
        {!clientOnly && (
          <Tabs<Gallery>
            label="Galleries"
            current={galleryTab}
            onChange={setGalleryTab}
            tabs={[
              { id: 'GUEST', label: 'Guest gallery' },
              { id: 'CLIENT', label: 'Client gallery' },
            ]}
          />
        )}
        {(clientOnly ? ['CLIENT' as const] : ['GUEST' as const, 'CLIENT' as const]).map((gallery) => {
          const off = gallery === 'GUEST' ? event.liveGalleryEnabled === false : event.clientGalleryEnabled === false
          return (
            <div key={gallery} {...galleryPanel(gallery)} className="space-y-6" data-testid={`gallery-${gallery.toLowerCase()}`}>
              <p className="text-sm text-muted">
                {gallery === 'GUEST'
                  ? 'Guests see these photos (compressed, from your cameras or uploaded here).'
                  : 'Only your client sees these, in full quality.'}
              </p>
              {off && (
                <div role="status" data-testid={`gallery-off-${gallery.toLowerCase()}`} className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
                  This gallery is off. Uploads are saved, but guests (or your client) can't see them until it's turned on.
                </div>
              )}
              <UploadPhotosPanel
                eventId={event.id}
                gallery={gallery}
                categories={categories[gallery]}
                onAddStorage={() => openTab('overview')}
                onUploaded={() => {
                  loadStats()
                  loadCategories()
                }}
              />
              <CategoriesCard eventId={event.id} gallery={gallery} categories={categories[gallery]} onChange={loadCategories} />
              <PhotosPanel
                eventId={event.id}
                gallery={gallery}
                eventSlug={event.slug}
                eventName={event.name}
                coverPhotoId={event.coverPhotoId}
                refreshKey={(stats?.readyPhotos ?? 0) + (stats?.failedPhotos ?? 0) + (stats?.inProgressPhotos ?? 0)}
                uploaders={stats?.uploaders ?? []}
                categories={categories[gallery]}
                onCategoriesChanged={loadCategories}
                onCoverChange={(id) => update({ coverPhotoId: id })}
                onEventChanged={() => adminApi.event(eventId).then(setEvent).catch(() => {})}
                onPhotosChanged={loadStats}
              />
            </div>
          )
        })}
      </section>

      {!clientOnly && (
        <section {...panel('guests')} className="anim-fade-in">
          <AnalyticsPanel eventId={event.id} live={event.status === 'LIVE'} eventName={event.name} />
        </section>
      )}

      <section {...panel('couple')} className="anim-fade-in">
        <ClientsCard eventId={event.id} eventName={event.name} eventSlug={event.slug} refreshKey={stats?.readyPhotos ?? 0} onClients={setEventClients} />
      </section>

      <section {...panel('settings')} className="anim-fade-in space-y-6">
        {superAdmin && <GalleryAccessCard event={event} onChange={setEvent} />}
        <Card title="Privacy & data">
          <div className="flex flex-wrap items-end justify-between gap-4">
            {billing?.free ? (
              <Field label="Open until" hint="Guests and the couple can open the gallery until this date; photos and files are permanently removed then. Leave empty to keep.">
                <input
                  type="date"
                  className={`${inputClass} w-48`}
                  value={event.activeUntil ?? event.retentionUntil ?? ''}
                  onChange={(e) => update(e.target.value ? { retentionUntil: e.target.value } : { clearRetention: true })}
                />
              </Field>
            ) : (
              <p className="max-w-md text-sm text-muted">
                {event.retentionUntil
                  ? <>Photos and files are permanently removed on <b className="text-ink">{formatEventDate(event.retentionUntil)}</b>, 30 days after the gallery closes. To keep it longer, use <b className="text-ink">Keep 6 more months</b> on the Overview.</>
                  : 'This event is kept with no end date.'}
              </p>
            )}
            {superAdmin
              ? <Button variant="danger" onClick={deleteEvent}>Delete event now</Button>
              : <p className="max-w-xs text-xs text-muted" data-testid="no-delete">Events can't be deleted. You can delete photos from the Photos tab, or ask McreatiK to remove the event.</p>}
          </div>
        </Card>
      </section>
    </div>
  )
}
