import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminApp from './AdminApp'
import { auth } from './auth'

const event = {
  id: 'e1', name: 'Arun & Priya Wedding', slug: 'arun-priya-x', eventDate: '2026-12-01', startTime: null, endTime: null, status: 'LIVE',
  coverPhotoId: null, coverUrl: null, galleryUrl: 'https://live.test/e/arun-priya-x', retentionUntil: null, createdAt: '', updatedAt: '',
  ownerId: 's1', ownerName: 'Lens & Light', liveGalleryEnabled: true, clientGalleryEnabled: true,
}

function stats(over: Record<string, number> = {}) {
  return {
    totalPhotos: 19, readyPhotos: 19, inProgressPhotos: 0, failedPhotos: 0, storageBytes: 18_000_000, activeUploaders: 1,
    latestPhoto: null, uploaders: [], hiddenPhotos: 0, likedPhotos: 0, ...over,
  }
}

const ok = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** Every request of the current test, as "METHOD /path?query". */
let requests: string[] = []

function serve(statsBody = stats(), eventBody: Record<string, unknown> = event) {
  const patches: unknown[] = []
  requests = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input)).pathname
    const method = init?.method ?? 'GET'
    requests.push(`${method} ${String(input).replace(/^https?:\/\/[^/]+/, '')}`)
    if (path === '/api/admin/account') return ok({ email: 'me@test', displayName: 'Lens & Light', role: 'STUDIO', mustChangePassword: false })
    if (path === '/api/admin/events/e1' && method === 'PATCH') {
      const body = JSON.parse(String(init?.body))
      patches.push(body)
      return ok({ ...eventBody, ...body })
    }
    if (path === '/api/admin/events/e1') return ok(eventBody)
    if (path.endsWith('/stats')) return ok(statsBody)
    if (path.endsWith('/photos')) return ok({ photos: [], nextOffset: null })
    if (path.endsWith('/photo-facets')) return ok({ days: [], liked: 0, hidden: 0 })
    if (path.endsWith('/categories') || path.endsWith('/clients')) return ok([])
    if (path.endsWith('/album')) return ok({ limit: null, selected: [] })
    if (path.endsWith('/analytics')) return ok({ totals: { people: 0, qrScans: 0, visits: 0, photoViews: 0, downloads: 0, downloaders: 0, shares: 0, instagramClicks: 0 }, hours: [], topPhotos: [], lastActivityAt: null })
    return ok(null, 204)
  }))
  return patches
}

function Where() {
  const l = useLocation()
  return <output data-testid="where">{l.pathname + l.search}</output>
}

function open(path: string) {
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@test', displayName: 'Lens & Light', role: 'STUDIO', mustChangePassword: false })
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AdminApp />
      <Where />
    </MemoryRouter>,
  )
}

describe('Event dashboard', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('a client gallery has no Guests tab, gallery link, QR or cameras', async () => {
    serve(stats(), { ...event, eventType: 'CLIENT', liveGalleryEnabled: false })
    open('/events/e1?tab=guests')
    const tabs = await screen.findByRole('tablist', { name: 'Event sections' })
    expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Overview', 'Photos19', 'Couple', 'Settings'])
    expect(screen.getByRole('tabpanel', { name: 'Overview' })).toBeVisible()
    expect(screen.queryByRole('button', { name: 'Copy gallery link' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'QR code' })).not.toBeInTheDocument()
    expect(screen.getByTestId('event-stats')).not.toHaveTextContent('cameras online')
  })

  it('splits the event into tabs and keeps the open tab in the address', async () => {
    const user = userEvent.setup()
    serve()
    open('/events/e1')
    const tabs = await screen.findByRole('tablist', { name: 'Event sections' })
    expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['Overview', 'Photos19', 'Guests', 'Couple', 'Settings'])
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: 'Overview' })).toBeVisible()

    await user.click(screen.getByRole('tab', { name: /Settings/ }))
    expect(screen.getByTestId('where')).toHaveTextContent('/events/e1?tab=settings')
    // A studio keeps every event: no delete button, only McreatiK can remove one.
    expect(screen.getByRole('tabpanel', { name: 'Settings' })).not.toHaveTextContent('Delete event now')
    expect(screen.getByTestId('no-delete')).toHaveTextContent("Events can't be deleted")
    expect(screen.queryByRole('tabpanel', { name: 'Overview' })).not.toBeInTheDocument() // hidden, not removed

    // Arrow keys move between tabs.
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByTestId('where')).toHaveTextContent('?tab=couple')
  })

  it('opens straight on the tab in the link', async () => {
    serve()
    open('/events/e1?tab=photos')
    expect(await screen.findByRole('tab', { name: /Photos/ })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows one line of numbers, with problems only when there are some', async () => {
    serve(stats({ failedPhotos: 2 }))
    open('/events/e1')
    const strip = await screen.findByTestId('event-stats')
    expect(strip).toHaveTextContent('19 photos')
    expect(strip).toHaveTextContent('2 failed')
    expect(strip).not.toHaveTextContent('processing')
  })

  it('asks before archiving, and changes other statuses at once', async () => {
    const user = userEvent.setup()
    const patches = serve()
    open('/events/e1')
    await user.click(await screen.findByRole('button', { name: /Change status/ }))
    await user.click(screen.getByRole('menuitemradio', { name: /Archived/ }))
    expect(await screen.findByText('Archive this event?')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(patches).toEqual([])

    await user.click(screen.getByRole('button', { name: /Change status/ }))
    await user.click(screen.getByRole('menuitemradio', { name: /Completed/ }))
    await waitFor(() => expect(patches).toEqual([{ status: 'COMPLETED' }]))
  })

  describe('guest and client galleries', () => {
    it('has a Guest gallery and a Client gallery tab, each with its own explanation, upload box and list', async () => {
      const user = userEvent.setup()
      serve()
      open('/events/e1?tab=photos')
      const guest = await screen.findByTestId('gallery-guest')
      const client = screen.getByTestId('gallery-client')
      expect(screen.getByRole('tab', { name: 'Guest gallery' })).toHaveAttribute('aria-selected', 'true')
      expect(guest).not.toHaveAttribute('hidden')
      expect(client).toHaveAttribute('hidden')
      expect(guest).toHaveTextContent('Guests see these photos (compressed, from your cameras or uploaded here).')
      expect(within(guest).getByTestId('upload-compressed')).toBeInTheDocument()
      expect(within(guest).queryByTestId('upload-original')).toBeNull()
      expect(client).toHaveTextContent('Only your client sees these, in full quality.')
      expect(within(client).getByTestId('upload-original')).toBeInTheDocument()
      expect(within(client).queryByTestId('upload-compressed')).toBeNull()

      await user.click(screen.getByRole('tab', { name: 'Client gallery' }))
      expect(client).not.toHaveAttribute('hidden')
      expect(guest).toHaveAttribute('hidden')
      // Each gallery asks the server for its own photos, facets and categories.
      await waitFor(() => expect(requests.some((r) => r.includes('/e1/photos?') && r.includes('gallery=CLIENT'))).toBe(true))
      expect(requests.some((r) => r.includes('/e1/photos?') && r.includes('gallery=GUEST'))).toBe(true)
      expect(requests).toContain('GET /api/admin/events/e1/photo-facets?gallery=CLIENT')
      expect(requests).toContain('GET /api/admin/events/e1/categories?gallery=CLIENT')
      expect(requests).toContain('GET /api/admin/events/e1/categories?gallery=GUEST')
    })

    it('a client-type event shows the Client gallery only', async () => {
      serve(stats(), { ...event, eventType: 'CLIENT', liveGalleryEnabled: false })
      open('/events/e1?tab=photos')
      const client = await screen.findByTestId('gallery-client')
      expect(client).not.toHaveAttribute('hidden')
      expect(screen.queryByTestId('gallery-guest')).toBeNull()
      expect(screen.queryByRole('tab', { name: 'Guest gallery' })).toBeNull()
      // The guest gallery being off does not matter here: this is the client gallery.
      expect(screen.queryByTestId('gallery-off-client')).toBeNull()
    })

    it('says a gallery is off, while uploads keep working', async () => {
      serve(stats(), { ...event, liveGalleryEnabled: false })
      open('/events/e1?tab=photos')
      const note = await screen.findByTestId('gallery-off-guest')
      expect(note).toHaveTextContent("This gallery is off. Uploads are saved, but guests (or your client) can't see them until it's turned on.")
      expect(screen.queryByTestId('gallery-off-client')).toBeNull()
      expect(within(screen.getByTestId('gallery-guest')).getByRole('button', { name: 'Choose photos' })).toBeEnabled()
    })
  })
})
