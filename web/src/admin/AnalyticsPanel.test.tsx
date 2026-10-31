import { render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GuestAnalytics } from '../lib/types'
import { AnalyticsPanel } from './AnalyticsPanel'

const analytics: GuestAnalytics = {
  totals: { people: 200, qrScans: 150, visits: 250, photoViews: 2400, downloads: 180, downloaders: 90, shares: 0, instagramClicks: 21 },
  // 19:00 and 22:00 with quiet hours in between: the chart fills them in.
  hours: [
    { hour: '2026-10-31T19:00', people: 120, photoViews: 1500, downloads: 100 },
    { hour: '2026-10-31T22:00', people: 80, photoViews: 900, downloads: 80 },
  ],
  topPhotos: [{ photoId: 'p1', thumbnailUrl: 'https://media.test/t/1.jpg', fileName: 'IMG_1.JPG', views: 300, downloads: 40 }],
  lastActivityAt: new Date().toISOString(),
}

describe('Guest analytics', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows people, scans, views, downloads and Instagram taps with simple insights', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(analytics), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<AnalyticsPanel eventId="e1" live={false} />)
    const panel = await screen.findByTestId('analytics')
    for (const [label, value] of [['People', '200'], ['QR scans', '150'], ['Photo views', '2,400'], ['Downloads', '180'], ['Instagram taps', '21']]) {
      expect(within(panel).getAllByText(label)[0].parentElement).toHaveTextContent(value)
    }
    const insights = screen.getByTestId('analytics-insights')
    expect(insights).toHaveTextContent('45% of people saved a photo')
    expect(insights).toHaveTextContent('12.0 photos viewed per person')
    expect(insights).toHaveTextContent('60% of visits came from the QR')
    // 7 PM to 10 PM: four hourly bars, the quiet ones empty.
    const bars = within(screen.getByTestId('activity-chart')).getAllByRole('img')
    expect(bars.map((b) => b.getAttribute('aria-label')?.split(':')[0])).toEqual(['7 PM', '8 PM', '9 PM', '10 PM'])
    expect(bars[1]).toHaveAttribute('aria-label', '8 PM: 0 people, 0 photo views, 0 downloads')
    expect(within(screen.getByTestId('top-photos')).getByRole('img', { name: 'IMG_1.JPG' })).toBeInTheDocument()
  })
})
