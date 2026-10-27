import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AdminPhoto, UploaderStats } from '../lib/types'
import { PhotosPanel } from './PhotosPanel'

function adminPhoto(n: number, extra: Partial<AdminPhoto> = {}): AdminPhoto {
  return {
    id: `p${n}`, uploaderId: 'cam-a', originalFileName: `IMG_${n}.JPG`, status: 'READY', failureReason: null,
    fileSize: 1000, width: 2048, height: 1365, thumbnailUrl: `https://media.test/thumb/${n}.jpg`,
    webUrl: `https://media.test/web/${n}.jpg`, capturedAt: null, uploadedAt: null, readyAt: '2026-10-24T10:00:00Z',
    createdAt: '2026-10-24T10:00:00Z', originalAvailable: false, hidden: false, liked: false, ...extra,
  }
}

const uploaders = [
  { id: 'cam-a', name: 'MK Canon', photosUploaded: 2 },
  { id: 'cam-b', name: 'HP Canon', photosUploaded: 1 },
] as UploaderStats[]

interface Call { method: string; url: URL; body: unknown }

/** Fake dashboard API: `pages(query)` answers photo listings; PATCH/DELETE succeed. */
function mockApi(pages: (q: URLSearchParams) => { photos: AdminPhoto[]; nextOffset: number | null }) {
  const calls: Call[] = []
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    calls.push({ method, url, body })
    const ok = (data: unknown) => new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })
    if (url.pathname.endsWith('/photo-facets')) return ok({ days: [{ day: '2026-10-24', photos: 3 }], liked: 0, hidden: 0 })
    if (url.pathname.endsWith('/photos')) return ok(pages(url.searchParams))
    if (method === 'PATCH') return ok({ ...adminPhoto(Number(url.pathname.split('/p').pop())), ...(body as object) })
    if (method === 'DELETE') return new Response(null, { status: 204 })
    return new Response('{}', { status: 404 })
  })
  vi.stubGlobal('fetch', fn)
  return { calls, listings: () => calls.filter((c) => c.method === 'GET' && c.url.pathname.endsWith('/photos')) }
}

function renderPanel(props: Partial<Parameters<typeof PhotosPanel>[0]> = {}) {
  const callbacks = { onCoverChange: vi.fn(), onEventChanged: vi.fn(), onPhotosChanged: vi.fn() }
  const view = render(
    <PhotosPanel eventId="e1" eventSlug="gowtham-sangeetha" eventName="Gowtham & Sangeetha" coverPhotoId={null}
      refreshKey={0} uploaders={uploaders} {...callbacks} {...props} />,
  )
  return { ...view, ...callbacks }
}

const tiles = () => within(screen.getByTestId('admin-photos')).getAllByRole('listitem')

describe('Dashboard photos', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('shows Download, Hide and Delete on every photo, and Make cover under More', async () => {
    const user = userEvent.setup()
    mockApi(() => ({ photos: [adminPhoto(1)], nextOffset: null }))
    const { onCoverChange } = renderPanel()
    const tile = (await screen.findAllByRole('listitem'))[0]
    expect(within(tile).getByRole('button', { name: 'Download' })).toBeInTheDocument()
    expect(within(tile).getByRole('button', { name: 'Hide from gallery' })).toBeInTheDocument()
    expect(within(tile).getByRole('button', { name: 'Delete' })).toBeInTheDocument()
    await user.click(within(tile).getByLabelText('More'))
    await user.click(within(tile).getByRole('button', { name: /Make cover/ }))
    expect(onCoverChange).toHaveBeenCalledWith('p1')
  })

  it('likes and hides photos', async () => {
    const user = userEvent.setup()
    const api = mockApi(() => ({ photos: [adminPhoto(1)], nextOffset: null }))
    const { onPhotosChanged } = renderPanel()
    await screen.findAllByRole('listitem')

    await user.click(screen.getByRole('button', { name: 'Like' }))
    await waitFor(() => expect(api.calls.some((c) => c.method === 'PATCH' && (c.body as { liked?: boolean }).liked === true)).toBe(true))
    expect(await screen.findByRole('button', { name: 'Unlike' })).toHaveAttribute('aria-pressed', 'true')

    await user.click(screen.getByRole('button', { name: 'Hide from gallery' }))
    expect(await screen.findByText('Hidden')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show in gallery' })).toBeInTheDocument()
    expect(onPhotosChanged).toHaveBeenCalled()
  })

  it('opens a photo full screen with the dashboard actions', async () => {
    const user = userEvent.setup()
    mockApi(() => ({ photos: [adminPhoto(1), adminPhoto(2)], nextOffset: null }))
    renderPanel()
    await user.click(await screen.findByRole('button', { name: 'View IMG_2.JPG' }))
    const viewer = screen.getByRole('dialog', { name: 'Photo viewer' })
    expect(within(viewer).getByTestId('viewer-counter')).toHaveTextContent('2 / 2')
    expect(within(viewer).getByText(/IMG_2\.JPG · MK Canon/)).toBeInTheDocument()
    for (const name of ['Download', 'Like', 'Hide from gallery', 'Make cover', 'Delete']) {
      expect(within(viewer).getByRole('button', { name })).toBeInTheDocument()
    }
    await user.keyboard('{ArrowLeft}')
    expect(within(viewer).getByTestId('viewer-counter')).toHaveTextContent('1 / 2')
  })

  it('sends camera, liked, day and sort filters to the server', async () => {
    const user = userEvent.setup()
    const api = mockApi(() => ({ photos: [adminPhoto(1)], nextOffset: null }))
    renderPanel()
    await screen.findAllByRole('listitem')
    await user.selectOptions(screen.getByLabelText('Camera'), 'cam-b')
    await user.selectOptions(screen.getByLabelText('Show'), 'liked')
    await user.selectOptions(screen.getByLabelText('Day'), '2026-10-24')
    await user.selectOptions(screen.getByLabelText('Sort'), 'CAMERA')
    await waitFor(() => {
      const q = api.listings().at(-1)!.url.searchParams
      expect(Object.fromEntries(q)).toMatchObject({ uploaderId: 'cam-b', liked: 'true', day: '2026-10-24', sort: 'CAMERA', offset: '0' })
    })
  })

  it('stays in place after Load more and deleting, and loads on from where the list is', async () => {
    const user = userEvent.setup()
    const all = Array.from({ length: 5 }, (_, i) => adminPhoto(i + 1))
    const api = mockApi((q) => {
      const offset = Number(q.get('offset'))
      return { photos: all.slice(offset, offset + 2), nextOffset: offset + 2 < all.length ? offset + 2 : null }
    })
    const { rerender, onCoverChange, onEventChanged, onPhotosChanged } = renderPanel()
    await screen.findAllByRole('listitem')
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tiles()).toHaveLength(4))

    // Delete the 3rd photo (confirm in the dialog).
    await user.click(within(tiles()[2]).getByRole('button', { name: 'Delete' }))
    await user.click(await screen.findByRole('button', { name: 'Delete photo' }))
    await waitFor(() => expect(tiles()).toHaveLength(3))
    // The stats poll changes refreshKey: the list is merged, not reset to the first page.
    rerender(<PhotosPanel eventId="e1" eventSlug="gowtham-sangeetha" eventName="x" coverPhotoId={null} refreshKey={1}
      uploaders={uploaders} onCoverChange={onCoverChange} onEventChanged={onEventChanged} onPhotosChanged={onPhotosChanged} />)
    await waitFor(() => expect(api.listings()).toHaveLength(3))
    expect(tiles()).toHaveLength(3)
    expect(tiles().map((t) => t.querySelector('img')!.getAttribute('src'))).toEqual([
      'https://media.test/thumb/1.jpg', 'https://media.test/thumb/2.jpg', 'https://media.test/thumb/4.jpg',
    ])

    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(api.listings().at(-1)!.url.searchParams.get('offset')).toBe('3'))
  })
})
