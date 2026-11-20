import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EventDetail } from '../lib/types'
import { WatermarkCard } from './WatermarkCard'

const event = (over: Partial<EventDetail> = {}): EventDetail => ({
  id: 'e1', name: 'Arun & Priya', slug: 'arun-priya', eventDate: '2026-11-01', startTime: null, endTime: null, status: 'LIVE',
  coverPhotoId: null, coverUrl: null, galleryUrl: 'https://live.test/e/arun-priya', retentionUntil: null,
  createdAt: '', updatedAt: '', ownerId: 's1', ownerName: null, eventType: 'MAIN',
  brand: { name: 'Lens Studio', logoUrl: null, instagram: null, mcreatik: false }, ...over,
})

describe('Watermark card', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('is on by default, and says the studio name is used without a logo', () => {
    render(<WatermarkCard event={event()} onChange={vi.fn()} />)
    expect(screen.getByRole('switch', { name: /Studio logo/ })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByTestId('watermark-no-logo')).toBeInTheDocument()
    expect(screen.getByTestId('watermark-preview')).toHaveTextContent('Lens Studio')
  })

  it('switches it off for the event', async () => {
    const fetchMock = vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) =>
      new Response(JSON.stringify(event({ ...JSON.parse(String(init?.body)) })), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const onChange = vi.fn()
    render(<WatermarkCard event={event({ brand: { name: 'Lens Studio', logoUrl: 'https://media.test/logo.png', instagram: null, mcreatik: false } })} onChange={onChange} />)
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
    render(<WatermarkCard event={event({ watermark: false })} onChange={vi.fn()} />)
    expect(screen.getByRole('switch', { name: /Studio logo/ })).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText(/clean photos/)).toBeInTheDocument()
    expect(screen.queryByTestId('watermark-preview')).not.toBeInTheDocument()
  })
})
