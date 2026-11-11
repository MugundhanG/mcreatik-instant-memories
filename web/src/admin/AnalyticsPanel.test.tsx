import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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

  it('shows likes, switches the top-photo tabs and lists HD requests with a Done toggle', async () => {
    const user = userEvent.setup()
    const top = (id: string, likes: number) => ({ photoId: id, thumbnailUrl: `https://media.test/t/${id}.jpg`, fileName: `${id}.JPG`, views: 5, downloads: 2, likes })
    const full = { ...analytics, totals: { ...analytics.totals, likes: 12 }, mostLiked: [top('liked', 9)], mostDownloaded: [top('dl', 0)], mostViewed: [top('vw', 0)] }
    const requests = [
      { id: 'r1', photoId: 'p1', thumbnailUrl: null, fileName: 'a.jpg', name: 'Asha', contact: '9876543210', kind: 'WHATSAPP', done: false, createdAt: '2026-10-31T10:00:00Z' },
      { id: 'r2', photoId: 'p2', thumbnailUrl: null, fileName: 'b.jpg', name: null, contact: 'x@y.co', kind: 'EMAIL', done: false, createdAt: '2026-10-31T10:00:00Z' },
    ]
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url.includes('/hq-requests/r1')) return new Response(null, { status: 204 })
      if (url.endsWith('/hq-requests')) return new Response(JSON.stringify(requests), { status: 200, headers: { 'Content-Type': 'application/json' } })
      void init
      return new Response(JSON.stringify(full), { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AnalyticsPanel eventId="e1" live={false} eventName="Arun & Priya" />)
    const panel = await screen.findByTestId('analytics')
    expect(within(panel).getByText('Likes').parentElement).toHaveTextContent('12')
    expect(within(screen.getByTestId('top-photos')).getByRole('img', { name: 'liked.JPG' })).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Most downloaded' }))
    expect(within(screen.getByTestId('top-photos')).getByRole('img', { name: 'dl.JPG' })).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'Most viewed' }))
    expect(within(screen.getByTestId('top-photos')).getByRole('img', { name: 'vw.JPG' })).toBeInTheDocument()

    const hq = await screen.findByTestId('hq-requests')
    const wa = await within(hq).findByRole('link', { name: '9876543210' })
    expect(wa.getAttribute('href')).toContain('https://wa.me/9876543210?text=')
    expect(decodeURIComponent(wa.getAttribute('href')!)).toContain('Hi Asha, here is your HD photo from Arun & Priya')
    expect(within(hq).getByRole('link', { name: 'x@y.co' })).toHaveAttribute('href', 'mailto:x@y.co')
    const [done] = within(hq).getAllByRole('checkbox')
    fireEvent.click(done)
    expect(done).toBeChecked()
    await waitFor(() => expect(fetchMock.mock.calls.some(([u, i]) => String(u).endsWith('/hq-requests/r1') && (i as RequestInit).method === 'PATCH')).toBe(true))
  })

  it('says when there are no HD requests', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => new Response(JSON.stringify(String(input).endsWith('/hq-requests') ? [] : analytics), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<AnalyticsPanel eventId="e1" live={false} />)
    expect(await screen.findByText('No requests yet')).toBeInTheDocument()
  })
})
