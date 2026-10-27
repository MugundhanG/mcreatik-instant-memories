import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

  it('Android/desktop: Download saves the photo without leaving the gallery, and Share is hidden', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:photo')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)

    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    expect(screen.queryByRole('button', { name: 'Share' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(createObjectURL).toHaveBeenCalled())
    const saved = await screen.findByText(/Saved to your phone/)
    // Right after saving, the toast invites the guest to follow the studio.
    expect(within(saved).getByRole('link', { name: /Follow @mcreatik.studios/ })).toHaveAttribute('href', 'https://www.instagram.com/mcreatik.studios/')
    expect(open).not.toHaveBeenCalled()
    // Bypasses the cached non-CORS copy the <img> loaded, which would make the CORS fetch fail.
    const call = vi.mocked(fetch).mock.calls.find(([input]) => String(input).endsWith('/web/3.jpg'))
    expect(call?.[1]).toMatchObject({ mode: 'cors', cache: 'no-store' })
  })

  it('when the photo cannot be fetched, explains press-and-hold instead of opening a new page', async () => {
    const user = userEvent.setup()
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
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1')
    const share = vi.fn(async (data: ShareData) => void data)
    Object.defineProperty(navigator, 'share', { value: share, configurable: true })
    Object.defineProperty(navigator, 'canShare', { value: () => true, configurable: true })

    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    // prefetched as soon as the photo is shown, so the tap can open the sheet immediately
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).endsWith('/web/3.jpg'))).toBe(true))
    await new Promise((r) => setTimeout(r, 0))

    await user.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(share).toHaveBeenCalledTimes(1))
    const files = (share.mock.calls[0][0] as ShareData).files ?? []
    expect(files[0].name).toMatch(/\.jpg$/)
    expect(files[0].type).toBe('image/jpeg')
  })

  it('Instagram/Facebook in-app browser: shows the press-and-hold hint', async () => {
    const user = userEvent.setup()
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Linux; Android 14) Instagram 312.0.0')
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))
    await user.click(screen.getByRole('button', { name: 'Download' }))
    expect(await screen.findByText(/Press and hold the photo/)).toBeInTheDocument()
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
})
