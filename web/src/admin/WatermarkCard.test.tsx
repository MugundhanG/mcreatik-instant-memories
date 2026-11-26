import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { fakeCanvas } from '../test/canvas'
import type { EventDetail } from '../lib/types'
import { WatermarkCard } from './WatermarkCard'

const event = (over: Partial<EventDetail> = {}): EventDetail => ({
  id: 'e1', name: 'Arun & Priya', slug: 'arun-priya', eventDate: '2026-11-01', startTime: null, endTime: null, status: 'LIVE',
  coverPhotoId: null, coverUrl: null, galleryUrl: 'https://live.test/e/arun-priya', retentionUntil: null,
  createdAt: '', updatedAt: '', ownerId: 's1', ownerName: null, eventType: 'MAIN',
  brand: { name: 'Lens Studio', logoUrl: null, instagram: null, mcreatik: false }, ...over,
})

describe('Watermark card', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })
  const renderCard = (e: EventDetail, onChange = vi.fn()) => render(<MemoryRouter><WatermarkCard event={e} onChange={onChange} /></MemoryRouter>)

  it('is on by default, and says the studio name is used without a logo', () => {
    const canvas = fakeCanvas()
    renderCard(event())
    expect(screen.getByRole('switch', { name: /Studio logo/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('watermark-no-logo')).toBeInTheDocument()
    // The preview draws the real mark: no logo and no Instagram, so the studio name.
    expect(screen.getByTestId('watermark-preview')).toBeInTheDocument()
    expect(canvas.texts).toContain('Lens Studio')
    expect(screen.getByRole('link', { name: 'Change how it looks' })).toHaveAttribute('href', '/admin/account')
  })

  it('switches it off for the event', async () => {
    const fetchMock = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) =>
      new Response(JSON.stringify(event({ ...JSON.parse(String(init?.body)) })), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const onChange = vi.fn()
    fakeCanvas()
    renderCard(event({ brand: { name: 'Lens Studio', logoUrl: 'https://media.test/logo.png', instagram: null, mcreatik: false } }), onChange)
    expect(screen.queryByTestId('watermark-no-logo')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('switch', { name: /Studio logo/ }))
    await waitFor(() => expect(onChange).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('/api/admin/events/e1')
    expect(init?.method).toBe('PATCH')
    expect(JSON.parse(String(init?.body))).toEqual({ watermark: false })
    expect(onChange.mock.calls[0][0].watermark).toBe(false)
  })

  it('shows clean photos wording when off', () => {
    fakeCanvas()
    renderCard(event({ watermark: false }))
    expect(screen.getByRole('switch', { name: /Studio logo/ })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText(/clean photos/)).toBeInTheDocument()
    expect(screen.queryByTestId('watermark-preview')).not.toBeInTheDocument()
  })
})
