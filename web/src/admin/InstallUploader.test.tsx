import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AdminRole } from '../lib/types'
import AdminApp from './AdminApp'
import { auth } from './auth'
import { detectPlatform, UPLOADER_DOWNLOADS } from './uploaderDownloads'

const ok = (data: unknown) => new Response(JSON.stringify(data), { status: 200, headers: { 'Content-Type': 'application/json' } })

function signIn(role: AdminRole) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input)).pathname
    if (path === '/api/admin/account') return ok({ email: 'x@studio.com', displayName: 'Lens & Light', role, mustChangePassword: false })
    if (path === '/api/admin/events') return ok([])
    return new Response('{}', { status: 404 })
  }))
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'x@studio.com',
    displayName: 'Lens & Light', role, mustChangePassword: false })
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[`/admin${path}`]}>
      <Routes>
        <Route path="/admin/*" element={<AdminApp />} />
      </Routes>
    </MemoryRouter>,
  )
}

describe('Install uploader', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it.each(['STUDIO', 'SUPER_ADMIN'] as const)('is in the nav bar for %s and opens the Windows and Mac downloads', async (role) => {
    signIn(role)
    renderAt('')
    await userEvent.click(await screen.findByRole('link', { name: /Install uploader/ }))
    expect(await screen.findByRole('heading', { name: 'McreatiK Instant Memories Uploader' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Download for Windows/ })).toHaveAttribute('href', UPLOADER_DOWNLOADS.windows.href)
    expect(screen.getByRole('link', { name: /Apple chip/ })).toHaveAttribute('href', UPLOADER_DOWNLOADS.macApple.href)
    expect(screen.getByRole('link', { name: /Intel/ })).toHaveAttribute('href', UPLOADER_DOWNLOADS.macIntel.href)
  })

  it('downloads are the stable release names the installer workflow publishes', () => {
    expect(UPLOADER_DOWNLOADS.windows.href).toBe(
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Windows.msi')
    expect(UPLOADER_DOWNLOADS.macApple.file).toBe('McreatiK-Instant-Memories-Uploader-Mac-AppleSilicon.dmg')
    expect(UPLOADER_DOWNLOADS.macIntel.file).toBe('McreatiK-Instant-Memories-Uploader-Mac-Intel.dmg')
  })

  it('recognises the computer so its download comes first', () => {
    expect(detectPlatform({ userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' })).toBe('windows')
    expect(detectPlatform({ userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)' })).toBe('mac')
    expect(detectPlatform({ userAgent: 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)' })).toBe('phone')
    expect(detectPlatform({ userAgent: 'Mozilla/5.0 (Linux; Android 14; Pixel 7) Mobile' })).toBe('phone')
    expect(detectPlatform({ userAgent: 'x', userAgentData: { platform: 'Windows' } })).toBe('windows')
  })
})
