import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { exifCapturedAt, fit } from './prepareUpload'
import { UploadPhotosPanel } from './UploadPhotosPanel'

vi.mock('./prepareUpload', async (actual) => ({
  ...(await actual<typeof import('./prepareUpload')>()),
  // jsdom cannot decode images: hand back ready-made versions.
  preparePhoto: vi.fn(async (file: File) => ({
    web: new Blob(['web']), thumbnail: new Blob(['thumb']), width: 6000, height: 4000,
    capturedAt: null, checksumSha256: file.name.padEnd(64, '0'),
  })),
}))

/** A JPEG holding only an EXIF block with DateTimeOriginal (little-endian TIFF). */
function jpegWithExif(date: string) {
  const tiff = new Uint8Array(64)
  const v = new DataView(tiff.buffer)
  tiff.set([0x49, 0x49, 0x2a, 0x00]); v.setUint32(4, 8, true)
  v.setUint16(8, 1, true); v.setUint16(10, 0x8769, true); v.setUint16(12, 4, true); v.setUint32(14, 1, true); v.setUint32(18, 26, true)
  v.setUint16(26, 1, true); v.setUint16(28, 0x9003, true); v.setUint16(30, 2, true); v.setUint32(32, 20, true); v.setUint32(36, 44, true)
  tiff.set(new TextEncoder().encode(date), 44)
  const head = [0xff, 0xd8, 0xff, 0xe1, 0, 2 + 6 + tiff.length, 0x45, 0x78, 0x69, 0x66, 0, 0]
  return new Uint8Array([...head, ...tiff, 0xff, 0xda]).buffer
}

describe('Photo preparation', () => {
  it('reads the camera time from EXIF', () => {
    expect(exifCapturedAt(jpegWithExif('2026:10:10 08:15:00'))).toBe('2026-10-10T08:15:00Z')
    expect(exifCapturedAt(new Uint8Array([0xff, 0xd8, 0xff, 0xda]).buffer)).toBeNull()
    expect(exifCapturedAt(new Uint8Array([1, 2, 3, 4]).buffer)).toBeNull()
  })

  it('scales the long edge down, never up', () => {
    expect(fit(6000, 4000, 2048)).toEqual({ width: 2048, height: 1365 })
    expect(fit(4000, 6000, 640)).toEqual({ width: 427, height: 640 })
    expect(fit(800, 600, 2048)).toEqual({ width: 800, height: 600 })
  })
})

describe('Upload photos panel', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('uploads the chosen photos, skips ones already there, and retries failures', async () => {
    const user = userEvent.setup({ applyAccept: false })
    let failOnce = true
    const sent: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      const name = String((init!.body as FormData).get('fileName'))
      sent.push(name)
      const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
      if (name === 'b.jpg' && failOnce) {
        failOnce = false
        return json({ code: 'X', message: 'Network hiccup' }, 500)
      }
      return json({ outcome: name === 'c.jpg' ? 'DUPLICATE' : 'UPLOAD', photoId: name })
    }))
    const onUploaded = vi.fn()
    render(<UploadPhotosPanel eventId="e1" onUploaded={onUploaded} />)

    const files = ['a.jpg', 'b.jpg', 'c.jpg', 'notes.txt'].map((n) => new File(['x'], n, { type: n.endsWith('.txt') ? 'text/plain' : 'image/jpeg' }))
    await user.upload(screen.getByLabelText('Photos to upload'), files)

    const progress = await screen.findByTestId('upload-progress')
    await waitFor(() => expect(progress).toHaveTextContent('3 of 3 done'))
    expect(progress).toHaveTextContent('1 added to the gallery')
    expect(progress).toHaveTextContent('1 already there (skipped)')
    expect(progress).toHaveTextContent('b.jpg: Network hiccup')
    expect(sent).not.toContain('notes.txt')
    expect(onUploaded).toHaveBeenCalled()

    await user.click(screen.getByRole('button', { name: 'Retry failed' }))
    await waitFor(() => expect(progress).toHaveTextContent('2 added to the gallery'))
    expect(progress).not.toHaveTextContent('failed')
  })
})
