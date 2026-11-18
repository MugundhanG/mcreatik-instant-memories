import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EventDetail } from '../lib/types'
import { auth } from './auth'
import { GalleryAccessCard } from './GalleryAccessCard'

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

  it('lets a studio or McreatiK turn the guest gallery off after confirming', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (_: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)))
      return new Response(JSON.stringify({ ...event, liveGalleryEnabled: false }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@mcreatik.com', displayName: 'McreatiK', role: 'SUPER_ADMIN', mustChangePassword: false })
    const onChange = vi.fn()
    render(<GalleryAccessCard event={event} superAdmin onChange={onChange} />)
    const live = screen.getByRole('switch', { name: 'Guest gallery' })
    expect(live).toHaveAttribute('aria-checked', 'true')
    await user.click(live)
    await user.click(screen.getByRole('button', { name: 'Turn off' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ liveGalleryEnabled: false })))
    expect(bodies).toEqual([{ liveGallery: false }])
  })

  const stub = (bodies: unknown[], reply: unknown, status = 200) =>
    vi.stubGlobal('fetch', vi.fn(async (_: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)))
      return new Response(JSON.stringify(reply), { status, headers: { 'Content-Type': 'application/json' } })
    }))
  const studio = () => auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@studio.com', displayName: 'Studio', role: 'STUDIO', mustChangePassword: false })

  it('lets the studio turn a gallery on after confirming', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    stub(bodies, { ...event, clientGalleryEnabled: true })
    studio()
    const onChange = vi.fn()
    render(<GalleryAccessCard event={{ ...event, clientGalleryEnabled: false }} onChange={onChange} />)
    await user.click(screen.getByRole('switch', { name: 'Client gallery' }))
    await user.click(screen.getByRole('button', { name: 'Turn on' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ clientGalleryEnabled: true })))
    expect(bodies).toEqual([{ clientGallery: true }])
  })

  it('tells the studio why a gallery cannot be turned on', () => {
    render(<GalleryAccessCard event={{ ...event, liveGalleryEnabled: false, liveGalleryBlocked: 'The paid period of this gallery ended on 2026-01-01.' }} onChange={vi.fn()} />)
    expect(screen.getByRole('switch', { name: 'Guest gallery' })).toBeDisabled()
    expect(screen.getByTestId('access-blocked-liveGallery')).toHaveTextContent('paid period')
    expect(screen.getByRole('switch', { name: 'Client gallery' })).toBeEnabled()
  })

  it('shows the server refusal when turning on is not allowed', async () => {
    const user = userEvent.setup()
    stub([], { code: 'GALLERY_CANNOT_TURN_ON', message: 'The client gallery is paused until storage is added.' }, 409)
    studio()
    render(<GalleryAccessCard event={{ ...event, clientGalleryEnabled: false }} onChange={vi.fn()} />)
    await user.click(screen.getByRole('switch', { name: 'Client gallery' }))
    await user.click(screen.getByRole('button', { name: 'Turn on' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('paused until storage')
  })

  it('hides the guest gallery switch on a client-gallery event', () => {
    render(<GalleryAccessCard event={{ ...event, eventType: 'CLIENT' }} onChange={vi.fn()} />)
    expect(screen.queryByRole('switch', { name: 'Guest gallery' })).not.toBeInTheDocument()
  })
})
