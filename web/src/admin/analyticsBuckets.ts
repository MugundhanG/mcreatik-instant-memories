import type { GuestAnalytics } from '../lib/types'

/** Longer than this, the chart shows days instead of hours. */
const MAX_HOURS = 48

export interface Bucket {
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
export function buckets(hours: GuestAnalytics['hours']): { unit: 'hour' | 'day'; items: Bucket[] } {
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

