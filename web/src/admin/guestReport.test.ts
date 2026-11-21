import { describe, expect, it } from 'vitest'
import type { GuestAnalytics } from '../lib/types'
import { ROWS_PER_PAGE, insightLines, paginate, reportSvgs, summarySvg, type ReportInput } from './guestReport'

const measure = (text: string, font: string) => text.length * Number(/(\d+)px/.exec(font)?.[1] ?? 16) * 0.5

const photo = (id: string, n: number) => ({ photoId: id, thumbnailUrl: null, fileName: `${id}.JPG`, views: n, downloads: n, likes: n })
const hours = (n: number) => Array.from({ length: n }, (_, i) => {
  const d = new Date(Date.UTC(2026, 9, 1, i))
  return { hour: d.toISOString().slice(0, 13) + ':00', people: i + 1, photoViews: 10, downloads: 2 }
})
const data = (over: Partial<GuestAnalytics> = {}): GuestAnalytics => ({
  totals: { people: 200, qrScans: 150, visits: 250, photoViews: 2400, downloads: 180, downloaders: 90, shares: 3, instagramClicks: 21, likes: 12, contacts: 4 },
  hours: hours(6),
  topPhotos: [photo('a', 5)],
  mostLiked: [photo('l1', 9), photo('l2', 7)],
  lastActivityAt: null,
  ...over,
})
const input = (d: GuestAnalytics): ReportInput => ({
  eventName: 'Arun & Priya - Wedding Reception', dateLabel: '31 October 2026', studioName: 'Lens Studio', logo: null, generatedOn: '1 November 2026', data: d, measure,
})

describe('guest report', () => {
  it('summary page has the event, studio, totals and insights', () => {
    const svg = summarySvg(input(data()))
    for (const s of ['Arun &amp; Priya', 'Wedding Reception', '31 October 2026', 'LENS STUDIO', 'GUEST ACTIVITY REPORT', 'Contacted you', 'Instagram taps', '2,400', '45%', '12.0', '60%', 'Live photo gallery by McreatiK']) {
      expect(svg).toContain(s)
    }
    expect(svg).not.toContain('Find my photos')
  })

  it('shows the face-search block only when present', () => {
    const face = { searches: 10, failed: 1, guestsFound: 7, photosFound: 40, updates: 2, downloads: 5, likes: 3 }
    expect(summarySvg(input(data({ faceSearch: face })))).toContain('Find my photos')
  })

  it('paginates the activity table across pages', () => {
    expect(paginate(Array.from({ length: 120 }, (_, i) => i), ROWS_PER_PAGE)).toHaveLength(4)
    expect(paginate([], 30)).toEqual([[]])
    // 120 hours is over 48, so rows are days: use a long hourly spread of 100 days instead.
    const many = Array.from({ length: 100 }, (_, i) => ({ hour: new Date(Date.UTC(2026, 0, 1 + i)).toISOString().slice(0, 10) + 'T10:00', people: 1, photoViews: 1, downloads: 1 }))
    const pages = reportSvgs(input(data({ hours: many })))
    // summary + 4 table pages (100 days / 30) + top photos page
    expect(pages).toHaveLength(1 + 4 + 1)
    expect(pages[1]).toContain('Page 2 of 6')
    expect(pages[5]).toContain('Most loved')
  })

  it('lists insights like the panel', () => {
    expect(insightLines(data().totals)).toEqual(['45% of people saved a photo', '12.0 photos viewed per person', '60% of visits came from the QR', '3 shares'])
    expect(insightLines({ ...data().totals, people: 0 })).toEqual([])
  })
})
