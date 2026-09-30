import { render, screen, waitFor } from '@testing-library/react'
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
  afterEach(() => vi.unstubAllGlobals())

  it('opens full screen on tap with the optimized image and navigates with the keyboard', async () => {
    const user = userEvent.setup()
    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))

    const dialog = screen.getByRole('dialog', { name: 'Photo viewer' })
    expect(dialog).toBeInTheDocument()
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3')
    expect(screen.getByAltText('Photo 1 of 3')).toHaveAttribute('src', 'https://media.test/web/3.jpg')

    await user.keyboard('{ArrowRight}')
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3')
    await user.keyboard('{ArrowLeft}')
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('1 / 3')

    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('opens directly from a shared photo link', async () => {
    renderGallery(`/e/${SLUG}?photo=${photo(2).id}`)
    expect(await screen.findByRole('dialog', { name: 'Photo viewer' })).toBeInTheDocument()
    expect(screen.getByTestId('viewer-counter')).toHaveTextContent('2 / 3')
  })

  it('offers download and share', async () => {
    const user = userEvent.setup()
    const createObjectURL = vi.fn(() => 'blob:photo')
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL, revokeObjectURL: vi.fn() }))
    const clipboard = { writeText: vi.fn(async () => {}) }
    Object.defineProperty(navigator, 'clipboard', { value: clipboard, configurable: true })

    renderGallery()
    await user.click(await screen.findByRole('button', { name: 'Open photo 1' }))

    await user.click(screen.getByRole('button', { name: 'Download' }))
    await waitFor(() => expect(createObjectURL).toHaveBeenCalled())

    await user.click(screen.getByRole('button', { name: 'Share' }))
    await waitFor(() => expect(clipboard.writeText).toHaveBeenCalledWith(`${window.location.origin}/e/${SLUG}?photo=${photo(3).id}`))
    expect(await screen.findByText('Link copied')).toBeInTheDocument()
  })
})
