import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SelfieCamera } from './SelfieCamera'

function fakeStream(facingMode: string) {
  const track = { stop: vi.fn(), getSettings: () => ({ facingMode }) }
  return { track, stream: { getTracks: () => [track], getVideoTracks: () => [track] } as unknown as MediaStream }
}

function stubCamera(getUserMedia: (c: MediaStreamConstraints) => Promise<MediaStream>) {
  const fn = vi.fn(getUserMedia)
  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia: fn }, configurable: true })
  return fn
}

describe('Selfie camera', () => {
  afterEach(() => {
    Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true })
    vi.restoreAllMocks()
  })

  it('asks for the front camera only, with no way to switch or pick a saved photo', async () => {
    vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    const { stream } = fakeStream('user')
    const fn = stubCamera(async () => stream)
    render(<SelfieCamera onCapture={vi.fn()} onCancel={vi.fn()} />)
    expect(await screen.findByTestId('camera-preview')).toBeInTheDocument()
    expect(fn).toHaveBeenCalledWith(expect.objectContaining({ audio: false, video: expect.objectContaining({ facingMode: 'user' }) }))
    expect(screen.queryByRole('button', { name: /switch|flip|rotate/i })).not.toBeInTheDocument()
    expect(document.querySelector('input[type="file"]')).toBeNull()
  })

  it('refuses a back camera', async () => {
    const { stream, track } = fakeStream('environment')
    stubCamera(async () => stream)
    render(<SelfieCamera onCapture={vi.fn()} onCancel={vi.fn()} />)
    expect(await screen.findByTestId('camera-error')).toHaveTextContent('No front camera found')
    expect(track.stop).toHaveBeenCalled()
  })

  it('explains a blocked camera, and a browser without one', async () => {
    stubCamera(async () => {
      throw new DOMException('no', 'NotAllowedError')
    })
    const { unmount } = render(<SelfieCamera onCapture={vi.fn()} onCancel={vi.fn()} />)
    expect(await screen.findByTestId('camera-error')).toHaveTextContent('Allow the camera')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    unmount()

    Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true })
    render(<SelfieCamera onCapture={vi.fn()} onCancel={vi.fn()} />)
    expect(screen.getByTestId('camera-error')).toHaveTextContent('Open in Chrome or Safari')
  })
})
