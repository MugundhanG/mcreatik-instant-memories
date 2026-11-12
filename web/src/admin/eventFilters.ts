import type { EventStatus, EventSummary, EventType } from '../lib/types'

export type SortKey = 'newest' | 'oldest' | 'name'

export interface EventFilter {
  type: EventType | ''
  status: EventStatus | ''
  studio: string
  year: string
  month: string
  from: string
  to: string
  sort: SortKey
}

export const EMPTY_FILTER: EventFilter = { type: '', status: '', studio: '', year: '', month: '', from: '', to: '', sort: 'newest' }

const KEYS = Object.keys(EMPTY_FILTER) as (keyof EventFilter)[]

export function filterFromParams(p: URLSearchParams): EventFilter {
  const f = { ...EMPTY_FILTER }
  for (const k of KEYS) {
    const v = p.get(k)
    if (v) (f as Record<string, string>)[k] = v
  }
  return f
}

/** Only the non-default values, so the address stays short and "no filters" is a clean /admin. */
export function filterToParams(f: EventFilter): URLSearchParams {
  const p = new URLSearchParams()
  for (const k of KEYS) if (f[k] && f[k] !== EMPTY_FILTER[k]) p.set(k, f[k])
  return p
}

export const isFiltered = (f: EventFilter) => KEYS.some((k) => k !== 'sort' && f[k] !== '')

/** Event dates are plain yyyy-mm-dd, so the parts compare as text with no time zone surprises. */
export function applyFilter(events: EventSummary[], f: EventFilter): EventSummary[] {
  const out = events.filter((e) => {
    const d = e.eventDate
    if (f.type && (e.eventType ?? 'MAIN') !== f.type) return false
    if (f.status && e.status !== f.status) return false
    if (f.studio && e.ownerId !== f.studio) return false
    if (f.year && d.slice(0, 4) !== f.year) return false
    if (f.month && d.slice(5, 7) !== f.month) return false
    if (f.from && d < f.from) return false
    if (f.to && d > f.to) return false
    return true
  })
  const byDate = (a: EventSummary, b: EventSummary) => b.eventDate.localeCompare(a.eventDate)
  return out.sort(f.sort === 'oldest' ? (a, b) => -byDate(a, b) : f.sort === 'name' ? (a, b) => a.name.localeCompare(b.name) : byDate)
}
