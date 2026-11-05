import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { UploaderStats } from '../lib/types'
import { UploadersPanel } from './UploadersPanel'

describe('UploadersPanel', () => {
  it('links to the uploader installers for Windows and both kinds of Mac', () => {
    render(<UploadersPanel eventId="e1" uploaders={[]} onChange={() => {}} />)
    const links = ['Windows', 'Mac (M1–M4)', 'Mac (Intel)'].map((name) => screen.getByRole('link', { name }))
    expect(links.map((l) => l.getAttribute('href'))).toEqual([
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Windows.msi',
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Mac-AppleSilicon.dmg',
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Mac-Intel.dmg',
    ])
  })

  it('says where the connection code comes from', () => {
    const { rerender } = render(<UploadersPanel eventId="e1" uploaders={[]} onChange={() => {}} />)
    expect(screen.getByTestId('code-hint')).toHaveTextContent('press Add camera & get code')
    expect(screen.getByRole('button', { name: 'Add camera & get code' })).toBeInTheDocument()
    rerender(<UploadersPanel eventId="e1" uploaders={[{
      id: 'u1', name: 'Main', status: 'OFFLINE', lastSeenAt: null, deviceIdentifier: null, tokenPrefix: 'mku_abc',
      photosUploaded: 0, photosReady: 0, queuePending: 0, queueFailed: 0, lastError: null, lastUploadAt: null, paused: false,
    }]} onChange={() => {}} />)
    expect(screen.getByTestId('code-hint')).toHaveTextContent('Press Get new code on a camera')
    expect(screen.getByRole('button', { name: 'Get new code' })).toBeInTheDocument()
  })
})

describe('Pause and rename a camera', () => {
  const cam = (over: Partial<UploaderStats> = {}): UploaderStats => ({
    id: 'u1', name: 'Main', status: 'ONLINE', lastSeenAt: new Date().toISOString(), deviceIdentifier: null, tokenPrefix: 'mku_abc',
    photosUploaded: 12, photosReady: 12, queuePending: 0, queueFailed: 0, lastError: null, lastUploadAt: null, paused: false, ...over,
  })
  afterEach(() => vi.unstubAllGlobals())

  function server() {
    const calls: { method: string; path: string; body: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ method: init?.method ?? 'GET', path: new URL(String(input)).pathname, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      return new Response(null, { status: 204 })
    }))
    return calls
  }

  it('pauses a camera from the dashboard, and shows a paused camera with Resume', async () => {
    const user = userEvent.setup()
    const calls = server()
    const onChange = vi.fn()
    const { rerender } = render(<UploadersPanel eventId="e1" uploaders={[cam()]} onChange={onChange} />)
    await user.click(screen.getByRole('button', { name: 'Pause' }))
    await waitFor(() => expect(onChange).toHaveBeenCalled())
    expect(calls[0]).toEqual({ method: 'PATCH', path: '/api/admin/uploaders/u1', body: { paused: true } })

    rerender(<UploadersPanel eventId="e1" uploaders={[cam({ paused: true })]} onChange={onChange} />)
    expect(screen.getByText('Paused')).toBeInTheDocument()
    expect(screen.getByText(/Paused: new photos stay on the laptop/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Resume' }))
    await waitFor(() => expect(calls[1]).toEqual({ method: 'PATCH', path: '/api/admin/uploaders/u1', body: { paused: false } }))
  })

  it('renames a camera inline', async () => {
    const user = userEvent.setup()
    const calls = server()
    render(<UploadersPanel eventId="e1" uploaders={[cam()]} onChange={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Rename' }))
    const box = screen.getByRole('textbox', { name: 'New name for Main' })
    await user.clear(box)
    await user.type(box, 'Main - Karthik{Enter}')
    await waitFor(() => expect(calls[0]).toEqual({ method: 'PATCH', path: '/api/admin/uploaders/u1', body: { name: 'Main - Karthik' } }))
  })
})
