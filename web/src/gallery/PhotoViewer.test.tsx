import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushActivity } from '../lib/activity'
import { liveEvent, mockFetch, MockEventSource, photo } from '../test/mocks'
import GalleryPage from './GalleryPage'

const SLUG = 'arun-priya-7k3d'
const base = `/api/public/events/${SLUG}`
const IPHONE = 'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1'
const ANDROID = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36'
const ANDROID_INSTAGRAM = 'Mozilla/5.0 (Linux; Android 14) Instagram 312.0.0'
const apiDownload = (n: number) => `${base}/photos/${photo(n).id}/download`

/** Items the page reported to the analytics endpoint. */
function reportedTypes(fetchMock: ReturnType<typeof mockFetch>) {
  flushActivity()
  return fetchMock.mock.calls
    .map((c) => c as unknown as [RequestInfo | URL, RequestInit | undefined])
    .filter(([u]) => String(u).endsWith('/activity'))
    .flatMap(([, init]) => JSON.parse(String(init?.body)).items.map((i: { type: string }) => i.type))
}

function renderGallery(path = `/e/${SLUG}`) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/e/:slug" element={<GalleryPage />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('Photo viewer', () => {
  beforeEach(() => {
    vi.stubGlobal('EventSource', MockEventSource)
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
      '/web/3.jpg': () => new Response(new Uint8Array([0xff, 0xd8, 0xff]), { headers: { 'Content-Type': 'image/jpeg' } }),
    })
  })
  afterEach(() => {
    sessionStorage.clear()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
    for (const key of ['share', 'canShare'] as const) Reflect.deleteProperty(navigator, key)
  })

  it('opens full screen on tap with the optimized image and navigates with the keyboard', async () => {
    const user = userEvent.setup()
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))

    const dialog = screen.getByRole('dialog', { name: 'Photo viewer' })
    expect(dialog).toBeInTheDocument()
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3')
    expect(screen.getByAltText('Photo 1 of 3')).toHaveAttribute('src', 'https://media.test/web/3.jpg')

    await user.keyboard('{ArrowRight}')
    // Slides first, like a swipe, then shows the next photo.
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3')
    await waitFor(() => expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3'))
    await user.keyboard('{ArrowLeft}')
    await waitFor(() => expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3'))

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('counts against all photos in the event, not just the ones loaded so far', async () => {
    mockFetch({
      [base]: () => ({ ...liveEvent, photoCount: 2132 }),
      [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: 'more' }),
    })
    const user = userEvent.setup()
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 2' }))
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 2,132')
  })

  it('opens directly from a shared photo link', async () => {
    renderGallery(`/e/${SLUG}?photo=${photo(2).id}`)
    expect(await screen.findByRole('dialog', { name: 'Photo viewer' })).toBeInTheDocument()
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3')
  })

  it('Android/desktop: Download saves the photo without leaving the gallery', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:photo')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))

    await user.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(createObjectURL).toHaveBeenCalled())
    // Right after saving, a card at the top invites the guest to follow the studio (first download of the visit).
    const card = await screen.findByRole('dialog', { name: 'Follow on Instagram' })
    expect(card).toHaveTextContent('Saved to your phone')
    expect(within(card).getByRole('link', { name: 'Follow on Instagram' })).toHaveAttribute('href', 'https://www.instagram.com/mcreatik.studios/')
    await user.click(within(card).getByRole('button', { name: 'Not now' }))
    expect(screen.queryByRole('dialog', { name: 'Follow on Instagram' })).not.toBeInTheDocument()
    // Later downloads only confirm the save.
    await user.click(screen.getByRole('button', { name: 'Download' }))
    expect(await screen.findByText('Saved ✓')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Follow on Instagram' })).not.toBeInTheDocument()
    expect(open).not.toHaveBeenCalled()
    // Bypasses the cached non-CORS copy the <img> loaded, which would make the CORS fetch fail.
    // `dl=1` also gives the CDN a separate cache key from the <img> request.
    const call = vi.mocked(fetch).mock.calls.find(([input]) => String(input).endsWith('/web/3.jpg?dl=1'))
    expect(call?.[1]).toMatchObject({ mode: 'cors', cache: 'no-store' })
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).endsWith('/web/3.jpg'))).toBe(false)
  })

  it('watermark on: the photo is marked before saving, and the clean media file is never the fallback', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:photo')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
    // jsdom has no canvas image decoding: marking fails here, so the server-marked API download must be used.
    vi.stubGlobal('createImageBitmap', vi.fn(async () => { throw new Error('no decoder') }))
    const fetchMock = mockFetch({
      [base]: () => ({ ...liveEvent, watermark: true }),
      [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
      '/web/3.jpg': () => new Response(new Uint8Array([0xff, 0xd8, 0xff]), { headers: { 'Content-Type': 'image/jpeg' } }),
      [apiDownload(3)]: () => new Response(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]), { headers: { 'Content-Type': 'image/jpeg' } }),
    })
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    await user.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(createObjectURL).toHaveBeenCalled())
    const urls = fetchMock.mock.calls.map(([u]) => String(u))
    expect(urls.some((u) => u.includes(apiDownload(3)))).toBe(true)
    // Only the attempt to mark it in the browser read the media file; the saved file came from the API.
    const saved = (createObjectURL.mock.calls[0] as unknown as [Blob])[0]
    expect(saved.size).toBe(4)
  })

  it('guests get Like in place of Share', async () => {
    const user = userEvent.setup()
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    expect(screen.queryByRole('button', { name: 'Share' })).toBeNull()
    const like = screen.getByRole('button', { name: 'Like photo' })
    expect(like).toHaveTextContent('Like')
    await user.click(like)
    expect(like).toHaveTextContent('Liked')
  })
  it('iPhone: when every fetch fails, explains press-and-hold instead of opening a new page', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(IPHONE)
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(3)], nextCursor: null }),
      // no route for the image: the CORS fetch fails
    })
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    await user.click(screen.getByRole('button', { name: 'Download' }))
    expect(await screen.findByText(/Press and hold the photo/)).toBeInTheDocument()
    expect(open).not.toHaveBeenCalled()
  })

  it('iPhone: Download opens the Save Image sheet with the photo file', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(IPHONE)
    const share = vi.fn(async (data: ShareData) => void data)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })
    Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true })

    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    // prefetched as soon as the photo is shown, so the tap can open the sheet immediately
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).endsWith('/web/3.jpg?dl=1'))).toBe(true))
    await new Promise((r) => setTimeout(r, 0))

    await user.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1))
    const files = (share.mock.calls[0][0] as ShareData).files ?? []
    expect(files[0].name).toMatch(/\.jpg$/)
    expect(files[0].type).toBe('image/jpeg')
  })

  describe('download fallbacks and counting', () => {
    const jpegResponse = () => new Response(new Uint8Array([0xff, 0xd8, 0xff]), { headers: { 'Content-Type': 'image/jpeg' } })
    let createObjectURL: ReturnType<typeof vi.fn>
    let anchorClick: ReturnType<typeof vi.spyOn>
    let clickedHrefs: string[]

    function routes(extra: Record<string, () => unknown>) {
      return mockFetch({
        [base]: () => liveEvent,
        [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
        [`${base}/activity`]: () => new Response(null, { status: 204 }),
        [`${base}/categories`]: () => [],
        ...extra,
      })
    }

    beforeEach(() => {
      flushActivity() // earlier tests' queued activity is not this test's
      localStorage.clear()
      createObjectURL = vi.fn(() => 'blob:photo')
      vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
      clickedHrefs = []
      anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
        clickedHrefs.push(this.href)
      })
    })

    async function openAndDownload() {
      const user = userEvent.setup()
      renderGallery()
      await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
      await user.click(screen.getByRole('button', { name: 'Download' }))
      return user
    }

    it('media fetch fails: saves the blob from the API endpoint and counts the download', async () => {
      const fetchMock = routes({ [apiDownload(3)]: jpegResponse })
      await openAndDownload()
      expect(await screen.findByRole('dialog', { name: 'Follow on Instagram' })).toHaveTextContent('Saved to your phone')
      const urls = fetchMock.mock.calls.map(([u]) => String(u))
      expect(urls).toContain('https://media.test/web/3.jpg?dl=1')
      expect(urls.some((u) => u.endsWith(apiDownload(3)))).toBe(true)
      expect(createObjectURL).toHaveBeenCalledTimes(1)
      expect(anchorClick).toHaveBeenCalledTimes(1) // the blob save only
      expect(clickedHrefs[0]).toBe('blob:photo')
      expect(reportedTypes(fetchMock).filter((t) => t === 'DOWNLOAD')).toHaveLength(1)
    })

    it('Android: when both fetches fail, a plain link to the API download starts the download and counts', async () => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ANDROID)
      const fetchMock = routes({})
      await openAndDownload()
      await waitFor(() => expect(clickedHrefs).toHaveLength(1))
      expect(clickedHrefs[0]).toMatch(new RegExp(`${apiDownload(3)}$`))
      expect(createObjectURL).not.toHaveBeenCalled()
      expect(screen.queryByText(/Press and hold/)).not.toBeInTheDocument()
      await waitFor(() => expect(reportedTypes(fetchMock).filter((t) => t === 'DOWNLOAD')).toHaveLength(1))
    })

    it('iPhone: when every fetch fails the hint shows, and the download is still counted once', async () => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(IPHONE)
      const fetchMock = routes({})
      await openAndDownload()
      expect(await screen.findByText(/Press and hold the photo/)).toBeInTheDocument()
      expect(clickedHrefs).toHaveLength(0)
      expect(reportedTypes(fetchMock).filter((t) => t === 'DOWNLOAD')).toHaveLength(1)
    })

    it('iPhone: falls back to the API blob for the share sheet when the media fetch fails', async () => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(IPHONE)
      const share = vi.fn(async (data: ShareData) => void data)
      Object.defineProperty(navigator, 'share', { value: share, configurable: true })
      Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true })
      const fetchMock = routes({ [apiDownload(3)]: jpegResponse })
      renderGallery()
      const user = userEvent.setup()
      await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
      await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith(apiDownload(3)))).toBe(true))
      await new Promise((r) => setTimeout(r, 0))
      await user.click(screen.getByRole('button', { name: 'Download' }))
      await waitFor(() => expect(share).toHaveBeenCalledTimes(1))
      await waitFor(() => expect(reportedTypes(fetchMock).filter((t) => t === 'DOWNLOAD')).toHaveLength(1))
    })

    it('a cancelled share sheet is not counted', async () => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(IPHONE)
      const share = vi.fn(async () => {
        throw new DOMException('closed', 'AbortError')
      })
      Object.defineProperty(navigator, 'share', { value: share, configurable: true })
      Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true })
      const fetchMock = routes({ '/web/3.jpg': jpegResponse })
      renderGallery()
      const user = userEvent.setup()
      await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
      await waitFor(() => expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/web/3.jpg?dl=1'))).toBe(true))
      await new Promise((r) => setTimeout(r, 0))
      await user.click(screen.getByRole('button', { name: 'Download' }))
      await waitFor(() => expect(share).toHaveBeenCalled())
      expect(reportedTypes(fetchMock)).not.toContain('DOWNLOAD')
    })

    it('Android Instagram: tries the API link, then explains how to open Chrome (an intent link) and counts', async () => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(ANDROID_INSTAGRAM)
      const fetchMock = routes({})
      await openAndDownload()
      const notice = await screen.findByRole('dialog', { name: 'Open in your browser' })
      expect(notice).toHaveTextContent('Open this gallery in Chrome/Safari to download')
      const link = within(notice).getByRole('link', { name: 'Open in Chrome' })
      expect(link.getAttribute('href')).toMatch(/^intent:\/\/.+#Intent;scheme=https?;package=com\.android\.chrome;/)
      expect(clickedHrefs.some((h) => h.endsWith(apiDownload(3)))).toBe(true)
      expect(createObjectURL).not.toHaveBeenCalled()
      await waitFor(() => expect(reportedTypes(fetchMock).filter((t) => t === 'DOWNLOAD')).toHaveLength(1))
    })

    it('iPhone Instagram: tells the guest to tap the menu and Open in browser', async () => {
      vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(`${IPHONE} Instagram 312.0.0`)
      const fetchMock = routes({})
      await openAndDownload()
      const notice = await screen.findByRole('dialog', { name: 'Open in your browser' })
      expect(notice).toHaveTextContent('Tap ⋯ then Open in browser')
      expect(within(notice).queryByRole('link', { name: 'Open in Chrome' })).toBeNull()
      expect(clickedHrefs).toHaveLength(0)
      await waitFor(() => expect(reportedTypes(fetchMock).filter((t) => t === 'DOWNLOAD')).toHaveLength(1))
    })
  })

  describe('gestures', () => {
    const at = (x: number, y: number, pointerId = 1) => ({ pointerId, clientX: x, clientY: y })
    const drag = (el: HTMLElement, from: [number, number], to: [number, number]) => {
      fireEvent.pointerDown(el, at(...from))
      fireEvent.pointerMove(el, at((from[0] + to[0]) / 2, (from[1] + to[1]) / 2))
      fireEvent.pointerMove(el, at(...to))
      fireEvent.pointerUp(el, at(...to))
    }
    const tap = (el: HTMLElement, x = 100, y = 100) => {
      fireEvent.pointerDown(el, at(x, y))
      fireEvent.pointerUp(el, at(x, y))
    }
    const scaleOf = () => /scale\(([\d.]+)\)/.exec(screen.getByTestId('viewer-zoom').style.transform)?.[1]

    async function open() {
      const user = userEvent.setup()
      renderGallery()
      await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
      return screen.getByTestId('viewer-stage')
    }

    it('swiping left and right moves between photos', async () => {
      const stage = await open()
      drag(stage, [300, 200], [100, 210])
      await waitFor(() => expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3'))
      drag(stage, [100, 200], [300, 190])
      await waitFor(() => expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3'))
    })

    it('a short drag snaps back to the same photo', async () => {
      const stage = await open()
      fireEvent.pointerDown(stage, at(300, 200))
      fireEvent.pointerMove(stage, at(280, 200))
      await new Promise((r) => setTimeout(r, 300)) // slow: not a flick
      fireEvent.pointerMove(stage, at(270, 200))
      fireEvent.pointerUp(stage, at(270, 200))
      await new Promise((r) => setTimeout(r, 300))
      expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3')
    })

    it('pinching zooms in, and a swipe then pans instead of changing photo', async () => {
      const stage = await open()
      fireEvent.pointerDown(stage, at(100, 200, 1))
      fireEvent.pointerDown(stage, at(200, 200, 2))
      fireEvent.pointerMove(stage, at(50, 200, 1))
      fireEvent.pointerMove(stage, at(250, 200, 2))
      expect(Number(scaleOf())).toBeCloseTo(2)
      fireEvent.pointerUp(stage, at(50, 200, 1))
      fireEvent.pointerUp(stage, at(250, 200, 2))

      drag(stage, [300, 200], [100, 200])
      await new Promise((r) => setTimeout(r, 300))
      expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3')
      expect(Number(scaleOf())).toBeCloseTo(2)
    })

    it('double-tap zooms in and out; zoom resets on the next photo', async () => {
      const user = userEvent.setup()
      const stage = await open()
      tap(stage)
      tap(stage)
      expect(Number(scaleOf())).toBeCloseTo(2.5)
      await new Promise((r) => setTimeout(r, 350))
      tap(stage)
      tap(stage)
      expect(Number(scaleOf())).toBe(1)

      tap(stage)
      tap(stage)
      await user.keyboard('{ArrowRight}')
      await waitFor(() => expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3'))
      expect(Number(scaleOf())).toBe(1)
    })

    it('the Next button slides the photo across instead of jumping', async () => {
      const user = userEvent.setup()
      await open()
      await user.click(screen.getByRole('button', { name: 'Next photo' }))
      const track = screen.getByTestId('viewer-zoom').parentElement!.parentElement!
      expect(track.style.transition).toMatch(/transform/)
      expect(track.style.transform).toMatch(/translate3d\(-\d+px/)
      await waitFor(() => expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3'))
    })

    it('swiping down closes the viewer', async () => {
      const stage = await open()
      drag(stage, [200, 100], [205, 400])
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    })
  })

  it('toggles the heart, remembers it in this browser and tracks LIKE / UNLIKE', async () => {
    const user = userEvent.setup()
    const fetchMock = mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
      [`${base}/activity`]: () => new Response(null, { status: 204 }),
      [`${base}/categories`]: () => [],
    })
    localStorage.clear()
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    const heart = await screen.findByRole('button', { name: 'Like photo' })
    expect(heart).toHaveAttribute('aria-pressed', 'false')
    await user.click(heart)
    expect(heart).toHaveAttribute('aria-pressed', 'true')
    expect(JSON.parse(localStorage.getItem(`mcreatik.likes.${SLUG}`)!)).toHaveLength(1)
    await user.click(heart)
    expect(heart).toHaveAttribute('aria-pressed', 'false')
    flushActivity()
    const types = fetchMock.mock.calls
      .map((c) => c as unknown as [RequestInfo | URL, RequestInit | undefined])
      .filter(([u]) => String(u).endsWith('/activity'))
      .flatMap(([, init]) => JSON.parse(String(init?.body)).items.map((i: { type: string }) => i.type))
    expect(types).toContain('LIKE')
    expect(types).toContain('UNLIKE')
  })

  it('sends an HD request and shows the server error when it fails', async () => {
    const user = userEvent.setup()
    let attempt = 0
    mockFetch({
      [base]: () => liveEvent,
      [`${base}/photos`]: () => ({ photos: [photo(3), photo(2), photo(1)], nextCursor: null }),
      [`${base}/categories`]: () => [],
      [`${base}/hq-requests`]: () => (++attempt === 1
        ? new Response(JSON.stringify({ code: 'INVALID_CONTACT', message: 'Enter your email, or your WhatsApp number with 10 digits' }), { status: 400 })
        : new Response(null, { status: 204 })),
    })
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    await user.click(await screen.findByRole('button', { name: 'Get HD photo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Get HD photo' })
    await user.type(within(dialog).getByLabelText('Email or WhatsApp number'), 'abc')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Enter your email, or your WhatsApp number with 10 digits')
    await user.clear(within(dialog).getByLabelText('Email or WhatsApp number'))
    await user.type(within(dialog).getByLabelText('Email or WhatsApp number'), 'a@b.co')
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Request sent. The studio will send it to you.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Get HD photo' })).not.toBeInTheDocument()
  })
})
