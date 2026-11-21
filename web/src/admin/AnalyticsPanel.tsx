import { useCallback, useEffect, useRef, useState } from 'react'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate, timeAgo } from '../lib/format'
import type { GuestAnalytics, HqRequest, TopPhoto } from '../lib/types'
import { waLink } from '../shared/phone'
import { ShutterLoader } from '../shared/ShutterLoader'
import { buckets } from './analyticsBuckets'
import { generateReport, type ReportFormat } from './guestReport'
import { Button, Card, Stat, Tabs } from './ui'

const REFRESH_MS = 30_000
const BAR = '#b37a2c'
const plural = (n: number, word: string) => `${formatCount(n)} ${word}${n === 1 ? '' : 's'}`
const percent = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : '–')

/** Guest analytics for one event: how many people opened the gallery, what they viewed and saved. */
export function AnalyticsPanel({ eventId, live, eventName = 'the event', eventDate, studioName = '' }: { eventId: string; live: boolean; eventName?: string; eventDate?: string; studioName?: string }) {
  const [data, setData] = useState<GuestAnalytics | null>(null)
  const [error, setError] = useState<string | null>(null)

  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const load = useCallback(() => {
    adminApi.analytics(eventId).then((d) => {
      if (!mounted.current) return // answer arrived after the panel closed
      setData(d)
      setError(null)
    }).catch((e: Error) => mounted.current && setError(e.message))
  }, [eventId])

  useEffect(() => {
    load()
    if (!live) return
    const t = setInterval(load, REFRESH_MS)
    return () => clearInterval(t)
  }, [load, live])

  if (!data) {
    return (
      <Card title="Guest activity">
        {error ? <p className="text-sm text-muted">Analytics are not available right now.</p> : <ShutterLoader className="py-8" />}
      </Card>
    )
  }

  const t = data.totals
  return (
    <Card title="Guest activity" action={<ReportMenu eventId={eventId} eventName={eventName} dateLabel={eventDate ? formatEventDate(eventDate) : ''} studioName={studioName} data={data} />}>
      <div className="space-y-6" data-testid="analytics">
        <p className="-mt-1 text-xs text-muted">
          {data.lastActivityAt ? `Last activity ${timeAgo(data.lastActivityAt)}` : 'No guest activity yet. Share the QR code and it shows up here.'}
          {' · '}Anonymous: no names or numbers are collected.
        </p>

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-8">
          <Stat label="People" value={formatCount(t.people)} />
          <Stat label="QR scans" value={formatCount(t.qrScans)} />
          <Stat label="Gallery visits" value={formatCount(t.visits)} />
          <Stat label="Photo views" value={formatCount(t.photoViews)} />
          <Stat label="Downloads" value={formatCount(t.downloads)} />
          <Stat label="Likes" value={formatCount(t.likes ?? 0)} />
          <Stat label="Instagram taps" value={formatCount(t.instagramClicks)} />
          <Stat label="Contacted you" value={formatCount(t.contacts ?? 0)} />
        </div>

        {t.people > 0 && (
          <ul className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-muted" data-testid="analytics-insights">
            <li><b className="text-ink tabular-nums">{percent(t.downloaders, t.people)}</b> of people saved a photo</li>
            <li><b className="text-ink tabular-nums">{t.people > 0 ? (t.photoViews / t.people).toFixed(1) : '0'}</b> photos viewed per person</li>
            <li><b className="text-ink tabular-nums">{percent(t.qrScans, t.visits)}</b> of visits came from the QR</li>
            {t.shares > 0 && <li><b className="text-ink tabular-nums">{formatCount(t.shares)}</b> shares</li>}
          </ul>
        )}

        <ActivityChart hours={data.hours} />

        <TopPhotos data={data} />
        {data.faceSearch && <FaceSearchStats stats={data.faceSearch} />}
        <HqRequests eventId={eventId} eventName={eventName} />
      </div>
    </Card>
  )
}

/** "Generate report": a one-page picture to send to the couple, or a PDF that also holds all the numbers. */
function ReportMenu({ eventId, eventName, dateLabel, studioName, data }: { eventId: string; eventName: string; dateLabel: string; studioName: string; data: GuestAnalytics }) {
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const alive = useRef(true)
  useEffect(() => () => { alive.current = false }, [])

  const make = async (format: ReportFormat) => {
    setBusy(true)
    setError(null)
    try {
      await generateReport({ eventId, eventName, dateLabel, studioName, data, format })
      if (alive.current) setOpen(false)
    } catch (e) {
      if (alive.current) setError(e instanceof Error && e.message ? e.message : 'Could not make the report. Please try again.')
    } finally {
      if (alive.current) setBusy(false)
    }
  }

  return (
    <div className="relative" data-testid="report-menu">
      <Button onClick={() => setOpen((o) => !o)} disabled={busy} aria-expanded={open}>{busy ? 'Making report…' : 'Generate report'}</Button>
      {open && (
        <div className="absolute right-0 z-20 mt-2 w-60 space-y-2 rounded-xl border border-line bg-white p-3 shadow-lg">
          <p className="text-xs text-muted">Share it with your client, or keep it as data.</p>
          <Button className="w-full" disabled={busy} onClick={() => make('image')}>Download image</Button>
          <Button className="w-full" disabled={busy} onClick={() => make('pdf')}>Download PDF</Button>
          {error && <p role="alert" className="text-xs text-red-600">{error}</p>}
        </div>
      )}
    </div>
  )
}

/** People active per hour (or day): one series, so no legend; hover a bar for its numbers. */
function ActivityChart({ hours }: { hours: GuestAnalytics['hours'] }) {
  const [hover, setHover] = useState<number | null>(null)
  const { unit, items } = buckets(hours)
  if (items.length === 0) return null
  const max = Math.max(1, ...items.map((b) => b.people))
  // Label every n-th bar so labels never collide.
  const every = Math.max(1, Math.ceil(items.length / 8))
  const shown = hover !== null ? items[hover] : null

  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <p className="text-sm font-medium">People in the gallery, per {unit}</p>
        <p className="text-xs text-muted">peak {formatCount(max)}</p>
      </div>
      <div className="relative" onMouseLeave={() => setHover(null)}>
        {shown && hover !== null && (
          <div
            role="tooltip"
            className="pointer-events-none absolute -top-2 z-10 -translate-x-1/2 -translate-y-full rounded-lg bg-ink px-2.5 py-1.5 text-xs whitespace-nowrap text-white shadow-lg"
            style={{ left: `${((hover + 0.5) / items.length) * 100}%` }}
          >
            <b>{shown.label}</b> · {formatCount(shown.people)} people · {formatCount(shown.photoViews)} views · {formatCount(shown.downloads)} saves
          </div>
        )}
        <div className="flex h-40 items-end gap-[2px] border-b border-line" data-testid="activity-chart">
          {items.map((b, i) => (
            <div
              key={b.key}
              className="flex h-full min-w-0 flex-1 cursor-default items-end justify-center"
              onMouseEnter={() => setHover(i)}
              onClick={() => setHover(i)}
              role="img"
              aria-label={`${b.label}: ${b.people} people, ${b.photoViews} photo views, ${b.downloads} downloads`}
            >
              <div
                className="w-full max-w-6 rounded-t-[4px] transition-opacity"
                style={{ height: `${(b.people / max) * 100}%`, minHeight: b.people > 0 ? 2 : 0, background: BAR, opacity: hover === null || hover === i ? 1 : 0.45 }}
              />
            </div>
          ))}
        </div>
        <div className="mt-1 flex gap-[2px]">
          {items.map((b, i) => (
            <span key={b.key} className="min-w-0 flex-1 text-center text-[11px] whitespace-nowrap text-muted">
              {i % every === 0 ? b.label : ''}
            </span>
          ))}
        </div>
      </div>
      <details className="mt-2 text-xs text-muted">
        <summary className="cursor-pointer">Show as table</summary>
        <table className="mt-2 w-full text-left tabular-nums">
          <thead><tr className="text-ink"><th className="py-1 font-medium">{unit === 'hour' ? 'Hour' : 'Day'}</th><th className="font-medium">People</th><th className="font-medium">Photo views</th><th className="font-medium">Downloads</th></tr></thead>
          <tbody>
            {items.map((b) => (
              <tr key={b.key} className="border-t border-line"><td className="py-1">{unit === 'hour' ? `${b.key.slice(5, 10)} ${b.label}` : b.label}</td><td>{b.people}</td><td>{b.photoViews}</td><td>{b.downloads}</td></tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  )
}

/** "Find my photos": how guests used it. Shown only when the event has the add-on. */
function FaceSearchStats({ stats: f }: { stats: NonNullable<GuestAnalytics['faceSearch']> }) {
  return (
    <div data-testid="face-search-stats">
      <p className="mb-1 text-sm font-medium">Find my photos</p>
      <p className="mb-3 text-sm text-muted">
        {f.searches === 0
          ? 'No guest has used it yet. Put “Find your photos with a selfie” on your board or card.'
          : <><b className="text-ink tabular-nums">{plural(f.guestsFound, 'guest')}</b> found their photos, <b className="text-ink tabular-nums">{plural(f.photosFound, 'photo')}</b> found, <b className="text-ink tabular-nums">{plural(f.downloads, 'download')}</b> from face search.</>}
      </p>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Searches" value={formatCount(f.searches)} />
        <Stat label="Guests who found photos" value={formatCount(f.guestsFound)} />
        <Stat label="Photos found" value={formatCount(f.photosFound)} />
        <Stat label="Updates" value={formatCount(f.updates)} />
        <Stat label="Downloads" value={formatCount(f.downloads)} />
        <Stat label="Likes" value={formatCount(f.likes)} />
      </div>
      {f.failed > 0 && <p className="mt-2 text-xs text-muted">{formatCount(f.failed)} {f.failed === 1 ? 'search' : 'searches'} failed (no face seen or an unreadable photo).</p>}
    </div>
  )
}

type TopTab = 'loved' | 'downloaded' | 'viewed'

/** The photos guests loved, saved and viewed most, one tab each. */
function TopPhotos({ data }: { data: GuestAnalytics }) {
  const [tab, setTab] = useState<TopTab>('loved')
  const lists: Record<TopTab, { photos: TopPhoto[]; count: (p: TopPhoto) => string }> = {
    loved: { photos: data.mostLiked ?? data.topPhotos, count: (p) => plural(p.likes ?? 0, 'like') },
    downloaded: { photos: data.mostDownloaded ?? data.topPhotos, count: (p) => plural(p.downloads, 'save') },
    viewed: { photos: data.mostViewed ?? data.topPhotos, count: (p) => plural(p.views, 'view') },
  }
  const { photos, count } = lists[tab]
  if (!data.topPhotos.length && !photos.length) return null
  return (
    <div>
      <Tabs label="Top photos" current={tab} onChange={setTab} tabs={[
        { id: 'loved', label: 'Most loved' }, { id: 'downloaded', label: 'Most downloaded' }, { id: 'viewed', label: 'Most viewed' },
      ]} />
      {photos.length === 0 ? <p className="mt-3 text-sm text-muted">Nothing yet.</p> : (
        <ul className="mt-3 grid grid-cols-4 gap-2 sm:grid-cols-8" data-testid="top-photos">
          {photos.map((p) => (
            <li key={p.photoId} className="text-center">
              {p.thumbnailUrl
                ? <img src={p.thumbnailUrl} alt={p.fileName} loading="lazy" className="aspect-square w-full rounded-lg object-cover" />
                : <div className="aspect-square w-full rounded-lg bg-stone-100" />}
              <p className="mt-1 text-xs leading-tight text-muted tabular-nums">{count(p)}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

/** Guests asking for a full-quality photo: contact them, then tick Done. */
function HqRequests({ eventId, eventName }: { eventId: string; eventName: string }) {
  const [rows, setRows] = useState<HqRequest[] | null>(null)
  useEffect(() => {
    let active = true // ignore an answer that arrives after the panel closed
    adminApi.hqRequests(eventId)
      .then((r) => active && setRows(Array.isArray(r) ? r : []))
      .catch(() => active && setRows([]))
    return () => {
      active = false
    }
  }, [eventId])

  const toggle = (r: HqRequest) => {
    const set = (done: boolean) => setRows((cur) => cur?.map((x) => (x.id === r.id ? { ...x, done } : x)) ?? cur)
    set(!r.done)
    adminApi.setHqDone(eventId, r.id, !r.done).catch(() => set(r.done))
  }

  return (
    <div data-testid="hq-requests">
      <p className="mb-2 text-sm font-medium">HD photo requests</p>
      {rows === null ? null : rows.length === 0 ? <p className="text-sm text-muted">No requests yet</p> : (
        <ul className="divide-y divide-line rounded-xl border border-line">
          {rows.map((r) => {
            const who = r.name || 'there'
            const href = r.kind === 'WHATSAPP'
              ? waLink(r.contact, `Hi ${who}, here is your HD photo from ${eventName}`)
              : `mailto:${r.contact}`
            return (
              <li key={r.id} className="flex items-center gap-3 p-2.5">
                {r.thumbnailUrl
                  ? <img src={r.thumbnailUrl} alt={r.fileName} className="size-12 shrink-0 rounded-lg object-cover" />
                  : <div className="size-12 shrink-0 rounded-lg bg-stone-100" />}
                <div className="min-w-0 flex-1 text-sm">
                  <p className="truncate font-medium">{r.name || 'No name'}</p>
                  <a href={href} target={r.kind === 'WHATSAPP' ? '_blank' : undefined} rel="noreferrer" className="block truncate text-muted underline underline-offset-2">{r.contact}</a>
                </div>
                <label className="flex shrink-0 items-center gap-1.5 text-sm">
                  <input type="checkbox" checked={r.done} onChange={() => toggle(r)} className="size-4" />
                  Done
                </label>
              </li>
            )
          })}
        </ul>
      )}
    </div>
  )
}
