import { describe, expect, it } from 'vitest'
import type { EventSummary } from '../lib/types'
import { applyFilter, EMPTY_FILTER, filterFromParams, filterToParams } from './eventFilters'

const ev = (id: string, name: string, eventDate: string, extra: Partial<EventSummary> = {}): EventSummary =>
  ({ id, name, slug: id, eventDate, status: 'LIVE', galleryUrl: '', readyPhotos: 0, createdAt: '', ownerId: 'o1', ownerName: 'Studio A', ...extra })

const events = [ev('a', 'Beta', '2026-03-10'), ev('b', 'Alpha', '2026-11-02', { status: 'COMPLETED', eventType: 'QUICK' }), ev('c', 'Gamma', '2025-03-20', { ownerId: 'o2' })]

describe('event filters', () => {
  it('sorts newest first by default, oldest or by name on request', () => {
    expect(applyFilter(events, EMPTY_FILTER).map((e) => e.id)).toEqual(['b', 'a', 'c'])
    expect(applyFilter(events, { ...EMPTY_FILTER, sort: 'oldest' }).map((e) => e.id)).toEqual(['c', 'a', 'b'])
    expect(applyFilter(events, { ...EMPTY_FILTER, sort: 'name' }).map((e) => e.id)).toEqual(['b', 'a', 'c'])
  })
  it('filters by type, status, studio, year, month and a custom range', () => {
    expect(applyFilter(events, { ...EMPTY_FILTER, type: 'QUICK' }).map((e) => e.id)).toEqual(['b'])
    expect(applyFilter(events, { ...EMPTY_FILTER, status: 'COMPLETED' }).map((e) => e.id)).toEqual(['b'])
    expect(applyFilter(events, { ...EMPTY_FILTER, studio: 'o2' }).map((e) => e.id)).toEqual(['c'])
    expect(applyFilter(events, { ...EMPTY_FILTER, year: '2026' }).map((e) => e.id)).toEqual(['b', 'a'])
    expect(applyFilter(events, { ...EMPTY_FILTER, month: '03' }).map((e) => e.id)).toEqual(['a', 'c'])
    expect(applyFilter(events, { ...EMPTY_FILTER, from: '2026-03-10', to: '2026-03-10' }).map((e) => e.id)).toEqual(['a'])
  })
  it('round-trips through the address', () => {
    const f = { ...EMPTY_FILTER, year: '2026', sort: 'name' as const }
    expect(filterToParams(f).toString()).toBe('year=2026&sort=name')
    expect(filterFromParams(new URLSearchParams('year=2026&sort=name'))).toEqual(f)
  })
})
