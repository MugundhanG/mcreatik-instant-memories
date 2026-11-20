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
// The real camera needs a device: a stand-in "shutter" hands over a selfie. (SelfieCamera has its own tests.)
vi.mock('./SelfieCamera', () => ({
  SelfieCamera: ({ onCapture, onCancel }: { onCapture: (b: Blob) => void; onCancel: () => void }) => (
    <div data-testid="selfie-camera">
      <button type="button" onClick={() => onCapture(new Blob(['me'], { type: 'image/jpeg' }))}>Shutter</button>
      <button type="button" onClick={onCancel}>Cancel camera</button>
    </div>
  ),
}))
// The face scan's minimum time would only slow the tests down.
vi.mock('./FaceScan', () => ({ SCAN_MIN_MS: 0, FaceScan: () => <p data-testid="face-scan">Analyzing your face…</p> }))

const SLUG = 'arun-priya-7k3d'
const base = `/api/public/events/${SLUG}`
const MATCH_KEY = `mcreatik.facematch.${SLUG}`
const SELFIE_KEY = `mcreatik.faceselfie.${SLUG}`
const CHECK_KEY = `mcreatik.facecheck.${SLUG}`

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
const callsTo = (fetchMock: ReturnType<typeof mockFetch>, path: string) =>
  fetchMock.mock.calls.filter(([u]) => String(u).includes(path))

function routes(findMe: () => unknown, extra: Record<string, () => unknown> = {}, event: Record<string, unknown> = {}) {
  return mockFetch({
    [base]: () => ({ ...liveEvent, faceSearch: true, ...event }),
    [`${base}/photos`]: () => (event.privateGallery ? error(403, 'PRIVATE_GALLERY') : { photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
    [`${base}/find-me`]: findMe,
    ...extra,
  })
}

/** Opens Find my photos, agrees, and takes a selfie with the (stand-in) front camera. */
async function takeSelfie({ agree = true } = {}) {
  await userEvent.click(await screen.findByTestId('find-my-photos'))
  if (agree) {
    await userEvent.click(screen.getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(screen.getByRole('button', { name: 'Open the camera' }))
  }
  await userEvent.click(await screen.findByRole('button', { name: 'Shutter' }))
}

function setup() {
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
}

describe('Find my photos', () => {
  setup()

  it('is hidden unless the event has face search', async () => {
    routes(() => ({ photos: [], count: 0 }), {}, { faceSearch: false })
    renderGallery()
    await screen.findByRole('heading', { name: liveEvent.name })
    expect(screen.queryByTestId('find-my-photos')).not.toBeInTheDocument()
  })

  it('needs the guest to agree before the camera opens, and never offers a file picker', async () => {
    routes(() => ({ photos: [], count: 0 }))
    renderGallery()
    await userEvent.click(await screen.findByTestId('find-my-photos'))
    const dialog = screen.getByRole('dialog', { name: 'Find my photos' })
    expect(dialog).toHaveTextContent('not saved on our server')
    expect(within(dialog).getByRole('link', { name: 'privacy notice' })).toHaveAttribute('href', '/privacy')
    const go = within(dialog).getByRole('button', { name: 'Open the camera' })
    expect(go).toBeDisabled()
    expect(screen.queryByTestId('selfie-camera')).not.toBeInTheDocument()
    expect(document.querySelector('input[type="file"]')).toBeNull()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'I agree' }))
    await userEvent.click(go)
    expect(screen.getByTestId('selfie-camera')).toBeInTheDocument()
  })

  it('shows the face scan, then the guest’s photos, and lets them go back', async () => {
    let answer: (v: Response) => void = () => {}
    routes(() => new Promise<Response>((r) => {
      answer = r
    }))
    renderGallery()
    await takeSelfie()
    expect(await screen.findByTestId('face-scan')).toBeInTheDocument()
    answer(new Response(JSON.stringify({ photos: [photo(2)], count: 1, matchId: 'm-1' }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Search again' })).not.toBeInTheDocument()
    expect(screen.getByText('New photos of you appear here by themselves.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Back to all photos' }))
    expect(screen.getByTestId('my-photos')).toHaveTextContent('Photos of you (1)')
  })

  it.each([
    [400, 'NO_FACE', 'We couldn’t see a face clearly'],
    [429, 'FACE_SEARCH_LIMIT', 'Too many searches'],
    [500, 'BOOM', 'Something went wrong'],
  ])('shows a friendly message for %s %s', async (status, code, text) => {
    routes(() => error(status, code))
    renderGallery()
    await takeSelfie()
    expect(await screen.findByText(new RegExp(text))).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('keeps the selfie on this phone when nothing matches yet, and never asks for another', async () => {
    routes(() => ({ photos: [], count: 0, matchId: null }))
    renderGallery()
    await takeSelfie()
    expect(await screen.findByText('No photos of you yet')).toBeInTheDocument()
    expect(screen.getByText(/We’ll keep looking as new photos arrive/)).toBeInTheDocument()
    await waitFor(() => expect(localStorage.getItem(SELFIE_KEY)).toMatch(/^data:image\/jpeg;base64,/))
    expect(localStorage.getItem(MATCH_KEY)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Back to all photos' }))
    expect(screen.getByTestId('my-photos')).toHaveTextContent('Photos of you (0)')
    expect(screen.queryByTestId('find-my-photos')).not.toBeInTheDocument()
  })
})

describe('Find my photos: remembered guests', () => {
  setup()

  const remembered = (over: { hasNew?: boolean; refresh?: () => unknown } = {}) => routes(() => ({ photos: [], count: 0 }), {
    [`${base}/matches/m-1`]: () => ({ photos: [photo(2), photo(1)], count: 2, hasNew: !!over.hasNew }),
    [`${base}/matches/m-1/refresh`]: over.refresh ?? (() => ({ photos: [photo(3), photo(2), photo(1)], count: 3, hasNew: false })),
  })

  it('keeps only the match code after a search (the selfie is dropped once found)', async () => {
    routes(() => ({ photos: [photo(2)], count: 1, matchId: 'm-9' }))
    renderGallery()
    await takeSelfie()
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(localStorage.getItem(MATCH_KEY)).toBe('m-9')
    expect(localStorage.getItem(SELFIE_KEY)).toBeNull()
  })

  it('restores the photos on reload from the stored code, without a selfie or a search', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    localStorage.setItem(CHECK_KEY, String(Date.now()))
    const fetchMock = remembered({ hasNew: true })
    renderGallery()
    const card = await screen.findByTestId('my-photos')
    await waitFor(() => expect(card).toHaveTextContent('Photos of you (2)'))
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(0)
    expect(callsTo(fetchMock, '/refresh')).toHaveLength(0) // checked a moment ago: no paid update yet
  })

  it('adds new photos by itself when they are ready and an update is due', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    localStorage.setItem(CHECK_KEY, '0')
    const fetchMock = remembered({ hasNew: true })
    renderGallery()
    await waitFor(() => expect(screen.getByTestId('my-photos')).toHaveTextContent('Photos of you (3)'))
    expect(callsTo(fetchMock, '/refresh')).toHaveLength(1)
    expect(Number(localStorage.getItem(CHECK_KEY))).toBeGreaterThan(0)
  })

  it('looks again with the kept selfie until the guest is found', async () => {
    localStorage.setItem(SELFIE_KEY, 'data:image/jpeg;base64,bWU=')
    localStorage.setItem(CHECK_KEY, '0')
    const fetchMock = routes(() => ({ photos: [photo(2)], count: 1, matchId: 'm-5' }))
    renderGallery()
    await waitFor(() => expect(screen.getByTestId('my-photos')).toHaveTextContent('Photos of you (1)'))
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(1)
    expect(localStorage.getItem(MATCH_KEY)).toBe('m-5')
    expect(localStorage.getItem(SELFIE_KEY)).toBeNull()
  })

  it('Forget me deletes the match, clears the phone and brings back the normal button', async () => {
    localStorage.setItem(MATCH_KEY, 'm-1')
    localStorage.setItem(CHECK_KEY, String(Date.now()))
    const fetchMock = remembered()
    renderGallery()
    await userEvent.click(await screen.findByTestId('my-photos'))
    await userEvent.click(await screen.findByRole('button', { name: /Forget me/ }))
    expect(localStorage.getItem(MATCH_KEY)).toBeNull()
    expect(await screen.findByTestId('find-my-photos')).toBeInTheDocument()
    const calls = () => fetchMock.mock.calls as unknown as [string, RequestInit | undefined][]
    await waitFor(() => expect(calls().some(([u, init]) => String(u).endsWith('/matches/m-1') && init?.method === 'DELETE')).toBe(true))
  })

  it('silently forgets an unknown or expired code (404)', async () => {
    localStorage.setItem(MATCH_KEY, 'gone')
    routes(() => ({ photos: [], count: 0 }), { [`${base}/matches/gone`]: () => error(404, 'MATCH_NOT_FOUND') })
    renderGallery()
    expect(await screen.findByTestId('find-my-photos')).toBeInTheDocument()
    await waitFor(() => expect(localStorage.getItem(MATCH_KEY)).toBeNull())
  })
})

describe('Find my photos: private gallery', () => {
  setup()

  it('shows no photos until the selfie, then only the guest’s own', async () => {
    const fetchMock = routes(() => ({ photos: [photo(2)], count: 1, matchId: 'm-7' }), {}, { privateGallery: true })
    renderGallery()
    expect(await screen.findByTestId('private-gallery')).toHaveTextContent('This gallery is private')
    expect(screen.getByText('Private gallery')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-testid="photo-grid"] img, main img')).toHaveLength(0)
    expect(MockEventSource.instances).toHaveLength(0) // no live stream of everyone's photos
    await takeSelfie()
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(screen.getByTestId('my-photo-results')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Back to all photos' })).not.toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(1)
  })
})

describe('Find my photos: selfie quality check', () => {
  setup()

  it('shows tips with Retake instead of searching, and offers Use anyway only after two failed checks', async () => {
    vi.mocked(checkSelfie).mockResolvedValue({ ok: false, issues: ['dark', 'small'] })
    const fetchMock = routes(() => ({ photos: [photo(2)], count: 1, matchId: 'm-3' }))
    renderGallery()
    await takeSelfie()
    const tips = await screen.findByTestId('selfie-tips')
    expect(tips).toHaveTextContent('Move to better light')
    expect(tips).toHaveTextContent('Come closer')
    expect(screen.queryByRole('button', { name: 'Use anyway' })).not.toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(0) // no paid search was sent

    await userEvent.click(screen.getByRole('button', { name: 'Retake' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Shutter' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Use anyway' }))
    expect(await screen.findByRole('heading', { name: 'Photos of you (1)' })).toBeInTheDocument()
    expect(callsTo(fetchMock, '/find-me')).toHaveLength(1)
  })
})
