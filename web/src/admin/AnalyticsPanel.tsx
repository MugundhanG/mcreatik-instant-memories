import { useCallback, useEffect, useState } from 'react'
import { adminApi } from '../lib/api'
import { formatCount, timeAgo } from '../lib/format'
import type { GuestAnalytics } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { Card, Stat } from './ui'

const REFRESH_MS = 30_000
/** Longer than this, the chart shows days instead of hours. */
const MAX_HOURS = 48
const BAR = '#b37a2c'

interface Bucket {
  key: string
  label: string
  people: number
  photoViews: number
  downloads: number
}

const pad = (n: number) => String(n).padStart(2, '0')
/** "2026-10-31T19:00" (India time from the server) as a wall-clock date we can step through by hours. */
const parseHour = (h: string) => new Date(`${h}:00Z`)
const hourKey = (d: Date) => `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${pad(d.getUTCHours())}:00`
const hourLabel = (d: Date) => {
  const h = d.getUTCHours()
  return `${h % 12 || 12} ${h < 12 ? 'AM' : 'PM'}`
}
const dayLabel = (d: Date) => d.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' })

/** Hourly buckets with the quiet hours filled in, or daily ones for events spread over several days. */
function buckets(hours: GuestAnalytics['hours']): { unit: 'hour' | 'day'; items: Bucket[] } {
  if (hours.length === 0) return { unit: 'hour', items: [] }
  const first = parseHour(hours[0].hour)
  const last = parseHour(hours[hours.length - 1].hour)
  const span = (last.getTime() - first.getTime()) / 3_600_000 + 1
  const byKey = new Map(hours.map((h) => [h.hour, h]))
  if (span <= MAX_HOURS) {
    const items: Bucket[] = []
    for (let t = first.getTime(); t <= last.getTime(); t += 3_600_000) {
      const d = new Date(t)
      const h = byKey.get(hourKey(d))
      items.push({ key: hourKey(d), label: hourLabel(d), people: h?.people ?? 0, photoViews: h?.photoViews ?? 0, downloads: h?.downloads ?? 0 })
    }
    return { unit: 'hour', items }
  }
  // Per day: people are summed over hours (a guest active in two hours counts twice), as an activity measure.
  const days = new Map<string, Bucket>()
  for (const h of hours) {
    const key = h.hour.slice(0, 10)
    const b = days.get(key) ?? { key, label: dayLabel(parseHour(h.hour)), people: 0, photoViews: 0, downloads: 0 }
    b.people += h.people
    b.photoViews += h.photoViews
    b.downloads += h.downloads
    days.set(key, b)
  }
  return { unit: 'day', items: [...days.values()] }
}

const plural = (n: number, word: string) => `${formatCount(n)} ${word}${n === 1 ? '' : 's'}`
const percent = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : '–')

/** Guest analytics for one event: how many people opened the gallery, what they viewed and saved. */
export function AnalyticsPanel({ eventId, live }: { eventId: string; live: boolean }) {
  const [data, setData] = useState<GuestAnalytics | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    adminApi.analytics(eventId).then((d) => {
      setData(d)
      setError(null)
    }).catch((e: Error) => setError(e.message))
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
    <Card title="Guest activity">
      <div className="space-y-6" data-testid="analytics">
        <p className="-mt-1 text-xs text-muted">
          {data.lastActivityAt ? `Last activity ${timeAgo(data.lastActivityAt)}` : 'No guest activity yet. Share the QR code and it shows up here.'}
          {' · '}Anonymous: no names or numbers are collected.
        </p>

        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Stat label="People" value={formatCount(t.people)} />
          <Stat label="QR scans" value={formatCount(t.qrScans)} />
          <Stat label="Gallery visits" value={formatCount(t.visits)} />
          <Stat label="Photo views" value={formatCount(t.photoViews)} />
          <Stat label="Downloads" value={formatCount(t.downloads)} />
          <Stat label="Instagram taps" value={formatCount(t.instagramClicks)} />
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

        {data.topPhotos.length > 0 && (
          <div>
            <p className="mb-2 text-sm font-medium">Most loved photos</p>
            <ul className="grid grid-cols-4 gap-2 sm:grid-cols-8" data-testid="top-photos">
              {data.topPhotos.map((p) => (
                <li key={p.photoId} className="text-center">
                  {p.thumbnailUrl
                    ? <img src={p.thumbnailUrl} alt={p.fileName} loading="lazy" className="aspect-square w-full rounded-lg object-cover" />
                    : <div className="aspect-square w-full rounded-lg bg-stone-100" />}
                  <p className="mt-1 text-[11px] leading-tight text-muted tabular-nums">
                    {plural(p.views, 'view')}<br />{plural(p.downloads, 'save')}
                  </p>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </Card>
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
            <span key={b.key} className="min-w-0 flex-1 text-center text-[10px] whitespace-nowrap text-muted">
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
