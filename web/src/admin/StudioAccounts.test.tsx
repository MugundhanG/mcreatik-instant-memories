import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { Account, AdminRole, Studio } from '../lib/types'
import AdminApp from './AdminApp'
import { auth } from './auth'

const ok = (data: unknown, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })

const superStudio: Studio = {
  id: 's0', name: 'McreatiK Studios', email: 'me@mcreatik.com', role: 'SUPER_ADMIN', disabled: false,
  mustChangePassword: false, createdAt: '2026-01-01T00:00:00Z', events: 3, photos: 2132, storageBytes: 9e9,
}

interface Call { method: string; path: string; body: unknown }

function mockApi(account: Account, studios: Studio[] = [superStudio]) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined
    calls.push({ method, path: url.pathname, body })
    if (url.pathname === '/api/admin/account') return ok(account)
    if (url.pathname === '/api/admin/account/password') return new Response(null, { status: 204 })
    if (url.pathname === '/api/admin/events') return ok([])
    if (url.pathname === '/api/admin/studios' && method === 'GET') return ok(studios)
    if (url.pathname === '/api/admin/studios' && method === 'POST') {
      const b = body as { name: string; email: string }
      return ok({ ...superStudio, id: 's1', role: 'STUDIO', mustChangePassword: true, events: 0, photos: 0, storageBytes: 0, ...b }, 201)
    }
    return new Response('{}', { status: 404 })
  }))
  return calls
}

function signIn(role: AdminRole, mustChangePassword = false) {
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'x@studio.com', displayName: 'Lens & Light', role, mustChangePassword })
}

const renderAt = (path: string) =>
  render(
    <MemoryRouter initialEntries={[`/admin${path}`]}>
      <Routes>
        <Route path="/admin/*" element={<AdminApp />} />
      </Routes>
    </MemoryRouter>,
  )

const account = (role: AdminRole, mustChangePassword = false): Account => ({ email: 'x@studio.com', displayName: 'Lens & Light', role, mustChangePassword })

describe('Studio accounts', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('shows the Studios page link only to McreatiK (super admin)', async () => {
    mockApi(account('STUDIO'))
    signIn('STUDIO')
    const { unmount } = renderAt('')
    expect(await screen.findByRole('link', { name: 'Events' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Studios' })).not.toBeInTheDocument()
    unmount()

    mockApi(account('SUPER_ADMIN'))
    signIn('SUPER_ADMIN')
    renderAt('')
    expect(await screen.findByRole('link', { name: 'Studios' })).toBeInTheDocument()
  })

  it('sends a studio with a temporary password to choose its own first', async () => {
    const user = userEvent.setup()
    const calls = mockApi(account('STUDIO', true))
    signIn('STUDIO', true)
    renderAt('')
    expect(await screen.findByText(/Choose your own to continue/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('Temporary password'), 'temporary-123')
    await user.type(screen.getByLabelText(/^New password/), 'my-own-password')
    await user.type(screen.getByLabelText('Repeat new password'), 'my-own-password')
    await user.click(screen.getByRole('button', { name: /password/i }))

    await waitFor(() => expect(auth.session()?.mustChangePassword).toBe(false))
    expect(calls.find((c) => c.path === '/api/admin/account/password')?.body)
      .toEqual({ currentPassword: 'temporary-123', newPassword: 'my-own-password' })
  })

  it('creates a studio login and shows the message to send them', async () => {
    const user = userEvent.setup()
    const calls = mockApi(account('SUPER_ADMIN'))
    signIn('SUPER_ADMIN')
    renderAt('/studios')
    expect(await within(await screen.findByTestId('studios')).findByText('McreatiK Studios')).toBeInTheDocument()

    await user.type(screen.getByLabelText(/^Studio name/), 'Lens & Light')
    await user.type(screen.getByLabelText('Login email'), 'owner@lenslight.in')
    await user.type(screen.getByLabelText(/^WhatsApp number/), '98765 43210')
    const password = (screen.getByLabelText(/^Temporary password/) as HTMLInputElement).value
    expect(password).toMatch(/^[a-zA-Z2-9]{12}$/)
    await user.click(screen.getByRole('button', { name: 'Create studio login' }))

    const message = await screen.findByTestId('studio-credentials')
    expect(message).toHaveTextContent('owner@lenslight.in')
    expect(message).toHaveTextContent(`Temporary password: ${password}`)
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/admin/studios')?.body)
      .toEqual({ name: 'Lens & Light', email: 'owner@lenslight.in', password })

    // Ready to send: WhatsApp opens the typed number's chat, email is addressed to the studio.
    const whatsapp = screen.getByRole('link', { name: /Send on WhatsApp/ })
    expect(whatsapp.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/919876543210\?text=/)
    expect(decodeURIComponent(whatsapp.getAttribute('href')!.split('text=')[1])).toContain(`Temporary password: ${password}`)
    const email = screen.getByRole('link', { name: /Send email/ })
    expect(email.getAttribute('href')).toMatch(/^mailto:owner%40lenslight\.in\?subject=/)
  })
})
