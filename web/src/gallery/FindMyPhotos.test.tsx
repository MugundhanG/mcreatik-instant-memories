import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { liveEvent, mockFetch, MockEventSource, photo } from '../test/mocks'
import GalleryPage from './GalleryPage'

vi.mock('../admin/prepareUpload', () => ({ prepareSelfie: vi.fn(async () => new Blob(['x'], { type: 'image/jpeg' })) }))

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
  afterEach(() => vi.unstubAllGlobals())

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
