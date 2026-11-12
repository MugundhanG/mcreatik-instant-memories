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
    if (url.pathname === '/api/admin/account/password') {
      account = { ...account, mustChangePassword: false }
      return ok({ token: 'fresh-token', expiresAt: new Date(Date.now() + 3_600_000).toISOString() })
    }
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

  it("lets McreatiK's own login edit its branding on the Account page and on the Studios page", async () => {
    const user = userEvent.setup()
    const brand = { name: 'McreatiK Studios', logoUrl: null, instagram: null, mcreatik: true, logoScale: 100, boardTheme: 'cream' as const, phone: null }
    mockApi({ ...account('SUPER_ADMIN'), brand }, [{ ...superStudio, brand }])
    signIn('SUPER_ADMIN')
    const { unmount } = renderAt('/account')
    expect(await screen.findByText('Studio branding')).toBeInTheDocument()
    unmount()
    renderAt('/studios')
    await user.click(await screen.findByRole('button', { name: /McreatiK Studios/ }))
    expect(screen.getByRole('button', { name: 'Edit branding' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete' })).not.toBeInTheDocument()
  })

  it('sends a studio with a temporary password to choose its own first', async () => {
    const user = userEvent.setup()
    const calls = mockApi(account('STUDIO', true))
    signIn('STUDIO', true)
    renderAt('')
    expect(await screen.findByText(/Choose your own to continue/)).toBeInTheDocument()

    await user.type(screen.getByLabelText('Temporary password', { selector: 'input' }), 'temporary-123')
    await user.type(screen.getByLabelText(/^New password/, { selector: 'input' }), 'my-own-password')
    await user.type(screen.getByLabelText('Repeat new password', { selector: 'input' }), 'my-own-password')
    await user.click(screen.getByRole('button', { name: 'Save password' }))

    await waitFor(() => expect(auth.session()?.mustChangePassword).toBe(false))
    // The change logs out other devices; this one keeps working with the fresh login.
    expect(auth.session()?.token).toBe('fresh-token')
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
    const password = (screen.getByLabelText(/^Temporary password/, { selector: 'input' }) as HTMLInputElement).value
    expect(password).toMatch(/^[a-zA-Z2-9]{12}$/)
    await user.click(screen.getByRole('button', { name: 'Create studio login' }))

    const message = await screen.findByTestId('studio-credentials')
    expect(message).toHaveTextContent('owner@lenslight.in')
    expect(message).toHaveTextContent(`Temporary password: ${password}`)
    expect(calls.find((c) => c.method === 'POST' && c.path === '/api/admin/studios')?.body)
      .toEqual({ name: 'Lens & Light', email: 'owner@lenslight.in', password, plan: 'PER_EVENT' })

    // Ready to send: WhatsApp opens the typed number's chat, email is addressed to the studio.
    const whatsapp = screen.getByRole('link', { name: /Send on WhatsApp/ })
    expect(whatsapp.getAttribute('href')).toMatch(/^https:\/\/wa\.me\/919876543210\?text=/)
    expect(decodeURIComponent(whatsapp.getAttribute('href')!.split('text=')[1])).toContain(`Temporary password: ${password}`)
    const gmail = new URL(screen.getByRole('link', { name: /Send email/ }).getAttribute('href')!)
    expect(gmail.origin).toBe('https://mail.google.com')
    expect(gmail.searchParams.get('to')).toBe('owner@lenslight.in')
    expect(gmail.searchParams.get('body')).toContain(`Temporary password: ${password}`)
    expect(screen.getByRole('link', { name: 'Open in mail app' }).getAttribute('href')).toMatch(/^mailto:owner@lenslight\.in\?subject=/)
  })

  it('lists each studio as a collapsed row that opens one at a time to its actions', async () => {
    const user = userEvent.setup()
    const studio = (id: string, name: string): Studio => ({ ...superStudio, id, name, email: `${id}@s.test`, role: 'STUDIO' })
    mockApi(account('SUPER_ADMIN'), [superStudio, studio('s1', 'Lens & Light'), studio('s2', 'Frame Co')])
    signIn('SUPER_ADMIN')
    renderAt('/studios')
    const list = await screen.findByTestId('studios')
    const first = await within(list).findByRole('button', { name: /Lens & Light/ })
    expect(within(list).queryByRole('button', { name: 'Reset password' })).not.toBeInTheDocument()
    await user.click(first)
    expect(within(list).getByRole('button', { name: 'Reset password' })).toBeInTheDocument()
    expect(within(list).getByRole('button', { name: 'Edit branding' })).toBeInTheDocument()
    await user.click(within(list).getByRole('button', { name: /Frame Co/ }))
    expect(within(list).getAllByRole('button', { name: 'Reset password' })).toHaveLength(1)
    expect(first).toHaveAttribute('aria-expanded', 'false')
  })
})
