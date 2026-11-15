import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { liveEvent, mockFetch, MockEventSource, photo } from '../test/mocks'
import { checkSelfie } from '../admin/selfieCheck'
import GalleryPage from './GalleryPage'

vi.mock('../admin/prepareUpload', () => ({ prepareSelfie: vi.fn(async () => new Blob(['x'], { type: 'image/jpeg' })) }))
vi.mock('../admin/selfieCheck', async (importActual) => ({
  ...(await importActual<typeof import('../admin/selfieCheck')>()),
  checkSelfie: vi.fn(async () => ({ ok: true, issues: [] })),
}))

const SLUG = 'arun-priya-7k3d'
const base = `/api/public/events/${SLUG}`

function renderGallery() {
  return render(
    <MemoryRouter initialEntries={[`/e/${SLUG}`]}>
      <Routes>
        <Route path="/e/:slug" element={<GalleryPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

const error = (status: number, code: string) => new Response(JSON.stringify({ code, message: code }), { status, headers: { 'Content-Type': 'application/json' } })
const selfie = () => new File(['img'], 'me.jpg', { type: 'image/jpeg' })

function routes(findMe: () => unknown, faceSearch = true) {
  return mockFetch({
    [base]: () => ({ ...liveEvent, faceSearch }),
    [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
    [`${base}/find-me`]: findMe,
  })
}

describe('Find my photos', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    Object.defineProperty(window, 'scrollY', { value: 0, writable: true, configurable: true })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('is hidden unless the event has face search', async () => {
    routes(() => ({ photos: [], count: 0 }), false)
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    expect(screen.queryByTestId('find-my-photos')).not.toBeInTheDocument()
  })

  it('needs the guest to agree before the camera or picker opens', async () => {
    routes(() => ({ photos: [], count: 0 }))
    const click = vi.spyOn(HTMLInputElement.prototype, 'click')
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))

    const dialog = screen.getByRole('dialog', { name: 'Find my photos' })
    expect(dialog).toHaveTextContent('your selfie is used once for this search and is not saved'.replace('your', 'Your'))
    expect(within(dialog).getByRole('link', { name: 'privacy notice' })).toHaveAttribute('href', '/privacy')
    const go = within(dialog).getByRole('button', { name: 'Take a selfie' })
    expect(go).toBeDisabled()
    expect(screen.getByTestId('selfie-input')).toHaveAttribute('capture', 'user')
    expect(click).not.toHaveBeenCalled()

    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(go)
    expect(click).toHaveBeenCalledTimes(1)
    click.mockRestore()
  })

  it('shows the photos of the guest, and lets them go back', async () => {
    const fetchMock = routes(() => ({ photos: [photo(2)], count: 1 }))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take a selfie' }))
    await userEvent.upload(screen.getByTestId('selfie-input'), selfie())

    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    const call = (fetchMock.mock.calls as unknown as [RequestInfo | URL, RequestInit][]).find(([u]) => String(u).endsWith('/find-me'))!
    expect(call[1].method).toBe('POST')
    expect((call[1].body as FormData).get('selfie')).toBeInstanceOf(Blob)
    expect(screen.getByRole('button', { name: 'Search again' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Back to all photos' }))
    await waitFor(() => expect(screen.queryByRole('heading', { name: /Photos of you/ })).not.toBeInTheDocument())
  })

  it('says so when no photos match yet', async () => {
    routes(() => ({ photos: [], count: 0 }))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take a selfie' }))
    await userEvent.upload(screen.getByTestId('selfie-input'), selfie())
    expect(await screen.findByText('No photos of you yet')).toBeInTheDocument()
    expect(screen.getByText(/New photos arrive during the event/)).toBeInTheDocument()
  })

  it.each([
    [400, 'NO_FACE', /couldn’t see a face clearly/],
    [429, 'FACE_SEARCH_LIMIT', /Too many searches/],
    [500, 'BOOM', /Something went wrong/],
  ])('shows a friendly message for %i %s', async (status, code, text) => {
    routes(() => error(status, code))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take a selfie' }))
    await userEvent.upload(screen.getByTestId('selfie-input'), selfie())
    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})

const MATCH_KEY = `mcreatik.facematch.${SLUG}`
type Call = [RequestInfo | URL, RequestInit | undefined]
const callsTo = (fetchMock: ReturnType<typeof mockFetch>, suffix: string, method?: string) =>
  (fetchMock.mock.calls as unknown as Call[]).filter(([u, init]) => new URL(String(u)).pathname.endsWith(suffix) && (!method || (init?.method ?? 'GET') === method))

function rememberedRoutes(opts: { hasNew?: boolean; refreshed?: unknown; match?: () => unknown } = {}) {
  return mockFetch({
    [base]: () => ({ ...liveEvent, faceSearch: true }),
    [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
    [`${base}/matches/m-1`]: opts.match ?? (() => ({ photos: [photo(2), photo(1)], count: 2, hasNew: opts.hasNew ?? false })),
    [`${base}/matches/m-1/refresh`]: () => opts.refreshed ?? { photos: [photo(3), photo(2), photo(1)], count: 3, hasNew: false },
    [`${base}/find-me`]: () => ({ photos: [photo(2)], count: 1, matchId: 'm-2' }),
  })
}

describe('Find my photos: remember me', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    Object.defineProperty(window, 'scrollY', { value: 0, writable: true, configurable: true })
    vi.mocked(checkSelfie).mockResolvedValue({ ok: true, issues: [] })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('keeps only the match code after a search, and nothing else', async () => {
    routes(() => ({ photos: [photo(2)], count: 1, matchId: 'm-9' }))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take a selfie' }))
    await userEvent.upload(screen.getByTestId('selfie-input'), selfie())
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(localStorage.getItem(MATCH_KEY)).toBe('m-9')
    const stored = Object.keys(localStorage).filter((k) => k.includes('facematch'))
    expect(stored).toEqual([MATCH_KEY])
    expect(screen.getByRole('button', { name: 'Update my photos' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Forget me' })).toBeInTheDocument()
  })

  it('restores the results on reload from the stored code, without a selfie or a search', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    const fetchMock = rememberedRoutes()
    renderGallery()
    const card = await screen.findByTestId('my-photos')
    expect(card).toHaveTextContent('Photos of you (2)')
    expect(card).toHaveTextContent('No new selfie needed')
    expect(screen.queryByTestId('find-my-photos')).not.toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(0)
    expect(callsTo(fetchMock, '/refresh')).toHaveLength(0)
    await userEvent.click(card)
    expect(await screen.findByRole('heading', { name: 'Photos of you (2)' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Update my photos' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Forget me' })).toBeInTheDocument()
  })

  it('hints when new photos may be ready, and Update my photos adds them', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    const fetchMock = rememberedRoutes({ hasNew: true })
    renderGallery()
    const card = await screen.findByTestId('my-photos')
    expect(card).toHaveTextContent('New photos may be ready')
    await userEvent.click(card)
    expect(screen.getByRole('status')).toHaveTextContent('New photos may be ready')
    await userEvent.click(screen.getByRole('button', { name: 'Update my photos' }))
    expect(await screen.findByRole('heading', { name: 'Photos of you (3)' })).toBeInTheDocument()
    expect(callsTo(fetchMock, '/matches/m-1/refresh', 'POST')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('Your photos are up to date.')
    expect(screen.queryByText(/New photos may be ready/)).not.toBeInTheDocument()
  })

  it('tells the guest when updating found nothing new, and when searching is limited', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    rememberedRoutes({ refreshed: { photos: [photo(2), photo(1)], count: 2, hasNew: false } })
    renderGallery()
    await userEvent.click(await screen.findByTestId('my-photos'))
    await userEvent.click(screen.getByRole('button', { name: 'Update my photos' }))
    expect(await screen.findByText('No new photos of you yet.')).toBeInTheDocument()
  })

  it('Forget me deletes the match, clears the phone and brings back the normal button', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    const fetchMock = rememberedRoutes()
    renderGallery()
    await userEvent.click(await screen.findByTestId('my-photos'))
    await userEvent.click(screen.getByRole('button', { name: 'Forget me' }))
    expect(await screen.findByTestId('find-my-photos')).toBeInTheDocument()
    expect(localStorage.getItem(MATCH_KEY)).toBeNull()
    expect(screen.queryByRole('heading', { name: /Photos of you/ })).not.toBeInTheDocument()
    expect(callsTo(fetchMock, '/matches/m-1', 'DELETE')).toHaveLength(1)
  })

  it('silently forgets an unknown or expired code (404)', async () => {
    localStorage.setItem(MATCH_KEY, 'old-code')
    mockFetch({
      [base]: () => ({ ...liveEvent, faceSearch: true }),
      [`${base}/photos`]: () => ({ photos: [photo(3)], nextCursor: null }),
      [`${base}/matches/old-code`]: () => error(404, 'MATCH_NOT_FOUND'),
    })
    renderGallery()
    expect(await screen.findByTestId('find-my-photos')).toBeInTheDocument()
    await waitFor(() => expect(localStorage.getItem(MATCH_KEY)).toBeNull())
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('a new search replaces the old match', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    const fetchMock = rememberedRoutes()
    renderGallery()
    await userEvent.click(await screen.findByTestId('my-photos'))
    await userEvent.upload(screen.getByTestId('selfie-input'), selfie())
    await waitFor(() => expect(localStorage.getItem(MATCH_KEY)).toBe('m-2'))
    expect(callsTo(fetchMock, '/matches/m-1', 'DELETE')).toHaveLength(1)
  })
})

describe('Find my photos: selfie quality check', () => {
  beforeEach(() => {
    MockEventSource.instances = []
    vi.stubGlobal('EventSource', MockEventSource)
    Object.defineProperty(window, 'scrollY', { value: 0, writable: true, configurable: true })
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
    vi.mocked(checkSelfie).mockResolvedValue({ ok: true, issues: [] })
  })

  async function takeSelfie() {
    await userEvent.upload(screen.getByTestId('selfie-input'), selfie())
  }

  it('shows tips with Retake instead of searching, and offers Use anyway only after two failed checks', async () => {
    vi.mocked(checkSelfie).mockResolvedValue({ ok: false, issues: ['dark', 'small'] })
    const fetchMock = routes(() => ({ photos: [photo(2)], count: 1, matchId: 'm-3' }))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take a selfie' }))

    await takeSelfie()
    const tips = await screen.findByTestId('selfie-tips')
    expect(tips).toHaveTextContent('Move to better light')
    expect(tips).toHaveTextContent('Come closer')
    expect(screen.getByRole('button', { name: 'Retake' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Use anyway' })).not.toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(0) // no paid search was sent

    await takeSelfie()
    await userEvent.click(await screen.findByRole('button', { name: 'Use anyway' }))
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(1)
  })

  it('a retake that passes goes straight to the search', async () => {
    vi.mocked(checkSelfie).mockResolvedValueOnce({ ok: false, issues: ['blurry'] })
    const fetchMock = routes(() => ({ photos: [photo(2)], count: 1 }))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Take a selfie' }))
    await takeSelfie()
    expect(await screen.findByTestId('selfie-tips')).toHaveTextContent('Hold the phone still')
    await takeSelfie()
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(1)
  })
})
