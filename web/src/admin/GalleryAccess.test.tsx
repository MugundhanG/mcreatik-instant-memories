import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EventDetail } from '../lib/types'
import { auth } from './auth'
import { GalleryAccessCard, GalleryAccessNotice } from './GalleryAccessCard'

const event = {
  id: 'e1', name: 'Wedding', slug: 'w', eventDate: '2026-12-01', startTime: null, endTime: null, status: 'LIVE',
  coverPhotoId: null, coverUrl: null, galleryUrl: 'https://live.test/e/w', retentionUntil: null, createdAt: '', updatedAt: '',
  ownerId: 's1', ownerName: 'Lens & Light', liveGalleryEnabled: true, clientGalleryEnabled: true,
} as EventDetail

describe('Gallery access switches', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('lets McreatiK turn the live gallery off after confirming', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (_: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)))
      return new Response(JSON.stringify({ ...event, liveGalleryEnabled: false }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@mcreatik.com', displayName: 'McreatiK', role: 'SUPER_ADMIN', mustChangePassword: false })
    const onChange = vi.fn()
    render(<GalleryAccessCard event={event} onChange={onChange} />)
    const live = screen.getByRole('switch', { name: 'Live gallery (guests)' })
    expect(live).toHaveAttribute('aria-checked', 'true')
    await user.click(live)
    await user.click(screen.getByRole('button', { name: 'Turn off' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ liveGalleryEnabled: false })))
    expect(bodies).toEqual([{ liveGallery: false }])
  })

  it('shows the studio a read-only notice when a gallery is off', () => {
    const { rerender } = render(<GalleryAccessNotice event={event} />)
    expect(screen.queryByTestId('gallery-access-notice')).not.toBeInTheDocument()
    rerender(<GalleryAccessNotice event={{ ...event, clientGalleryEnabled: false }} />)
    expect(screen.getByTestId('gallery-access-notice')).toHaveTextContent('Client gallery turned off by McreatiK')
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })
})
