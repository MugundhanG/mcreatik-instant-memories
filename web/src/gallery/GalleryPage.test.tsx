import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { liveEvent, mockFetch, MockEventSource, photo } from '../test/mocks'
import GalleryPage from './GalleryPage'

const SLUG = 'arun-priya-7k3d'
const base = `/api/public/events/${SLUG}`

function renderGallery(path = `/e/${SLUG}`) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/e/:slug" element={<GalleryPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const tiles = () => within(screen.getByTestId('photo-grid')).getAllByRole('listitem')

describe('Live gallery', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    Object.defineProperty(window, 'scrollY', { value: 0, writable: true, configurable: true })
  })
  afterEach(() => vi.unstubAllGlobals())

  it('shows the event name, live indicator and latest photos', async () => {
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
    })
    renderGallery()

    expect(await screen.findByRole('heading', { name: 'Arun & Priya Wedding' })).toBeInTheDocument()
    expect(screen.getByText('Live')).toBeInTheDocument()
    expect(screen.getByText('New photos are being added')).toBeInTheDocument()
    expect(tiles()).toHaveLength(3)
    const firstImg = tiles()[0].querySelector('img')!
    expect(firstImg).toHaveAttribute('src', 'https://media.test/thumb/3.jpg')
  })

  it('inserts a new photo in real time without a refresh', async () => {
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(2), photo(1)], nextCursor: null }),
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1))
    expect(MockEventSource.latest().url).toBe(`http://localhost:8080${base}/stream`)

    const p = photo(9)
    act(() => {
      MockEventSource.latest().emit('CONNECTED', {})
      MockEventSource.latest().emit('PHOTO_READY', { eventId: 'e1', photoId: p.id, ...p })
    })

    expect(tiles()).toHaveLength(3)
    expect(tiles()[0].querySelector('img')).toHaveAttribute('src', p.thumbnailUrl)
  })

  it('ignores duplicate PHOTO_READY messages', async () => {
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(1)], nextCursor: null }),
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1))
    const p = photo(1)
    act(() => MockEventSource.latest().emit('PHOTO_READY', { eventId: 'e1', photoId: p.id, ...p }))
    expect(tiles()).toHaveLength(1)
  })

  it('holds new photos behind a button while the guest is scrolled down', async () => {
    const user = userEvent.setup()
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(1)], nextCursor: null }),
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1))

    window.scrollY = 2000
    act(() => {
      for (const n of [5, 6]) {
        const p = photo(n)
        MockEventSource.latest().emit('PHOTO_READY', { eventId: 'e1', photoId: p.id, ...p })
      }
    })
    expect(tiles()).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: /2 new photos/ }))
    expect(tiles()).toHaveLength(3)
    expect(tiles()[0].querySelector('img')).toHaveAttribute('src', photo(6).thumbnailUrl)
  })

  it('catches up on photos missed while disconnected', async () => {
    const fetchMock = mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: (url) =>
        url.searchParams.get('after') === 'cursor-2'
          ? { photos: [photo(3), photo(4)], nextCursor: null }
          : { photos: [photo(2), photo(1)], nextCursor: null },
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1))

    act(() => MockEventSource.latest().fail())
    expect(await screen.findByText('Reconnecting…')).toBeInTheDocument()
    act(() => MockEventSource.latest().emit('CONNECTED', {}))

    await waitFor(() => expect(tiles()).toHaveLength(4))
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('after=cursor-2'))).toBe(true)
    expect(screen.getByText('New photos are being added')).toBeInTheDocument()
  })

  it('removes a photo deleted by the photographer', async () => {
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(2), photo(1)], nextCursor: null }),
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    await waitFor(() => expect(MockEventSource.instances).toHaveLength(1))
    act(() => MockEventSource.latest().emit('PHOTO_REMOVED', { eventId: 'e1', photoId: photo(2).id }))
    expect(tiles()).toHaveLength(1)
  })

  it('shows a friendly message for unknown or private galleries', async () => {
    mockFetch({})
    renderGallery()
    expect(await screen.findByText('This gallery isn’t available')).toBeInTheDocument()
  })

  it('shows an empty state before the first photo arrives', async () => {
    mockFetch({
      [base]: () => ({ ...liveEvent, photoCount: 0 }),
      [`${base}/photos`]: () => ({ photos: [], nextCursor: null }),
    })
    renderGallery()
    expect(await screen.findByText('The first photos will appear here soon')).toBeInTheDocument()
  })

  it('uses a two-column mobile-first grid that widens on larger screens', async () => {
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(1)], nextCursor: null }),
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    const grid = screen.getByTestId('photo-grid')
    expect(grid).toHaveClass('grid-cols-2')
    expect(grid.className).toMatch(/sm:grid-cols-3/)
    expect(grid.className).toMatch(/lg:grid-cols-4/)
  })

  it('does not show the live indicator for completed events', async () => {
    mockFetch({
      [base]: () => ({ ...liveEvent, status: 'COMPLETED', live: false }),
      [`${base}/photos`]: () => ({ photos: [photo(1)], nextCursor: null }),
    })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    expect(screen.queryByText('Live')).not.toBeInTheDocument()
  })
})
