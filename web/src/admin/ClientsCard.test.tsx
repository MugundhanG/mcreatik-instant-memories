import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { EventClient } from '../lib/types'
import { auth } from './auth'
import { ClientsCard } from './ClientsCard'

const ok = (d: unknown, status = 200) => new Response(JSON.stringify(d), { status, headers: { 'Content-Type': 'application/json' } })

const couple: EventClient = {
  id: 'c1', name: 'Arun & Priya', email: 'couple@test.in', phone: '98765 43210', mustChangePassword: true,
  disabled: false, createdAt: '2026-11-01T00:00:00Z', lastLoginAt: null,
}

function mockApi(clients: EventClient[]) {
  const calls: { method: string; path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    calls.push({ method, path: url.pathname, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    if (url.pathname.endsWith('/clients') && method === 'GET') return ok(clients)
    if (url.pathname.endsWith('/clients') && method === 'POST') return ok(couple, 201)
    if (url.pathname.endsWith('/album')) return ok({ limit: null, selected: [{ photoId: 'p1', fileName: 'IMG_1.JPG', thumbnailUrl: null, selectedAt: '', selectedBy: 'Arun & Priya', categoryId: null }] })
    if (url.pathname.endsWith('/downloads')) return ok({ path: '/api/public/downloads/zip_x', progressPath: '/api/public/downloads/zip_x/progress', photos: 1, fileName: 'W-album.zip' })
    if (url.pathname.endsWith('/progress')) return ok({ state: 'DONE', done: 1, total: 1, percent: 100 })
    return new Response(null, { status: 204 })
  }))
  return calls
}

describe('Client login card', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('shows the one client login of the event instead of the create form', async () => {
    mockApi([couple])
    auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 's@studio.in', displayName: 'S', role: 'STUDIO', mustChangePassword: false })
    const onClients = vi.fn()
    render(<ClientsCard eventId="e1" eventName="Wedding" refreshKey={0} onClients={onClients} />)
    const login = await screen.findByTestId('client-login')
    expect(login).toHaveTextContent('Arun & Priya')
    expect(login).toHaveTextContent('couple@test.in')
    expect(login).toHaveTextContent('98765 43210')
    expect(login).toHaveTextContent('Not logged in yet')
    expect(screen.queryByRole('button', { name: 'Create client login' })).not.toBeInTheDocument()
    expect(onClients).toHaveBeenCalledWith([couple])
  })

  it('creates the login with the WhatsApp number, then downloads the album selection as a ZIP', async () => {
    const user = userEvent.setup()
    const calls = mockApi([])
    const assign = vi.fn()
    vi.stubGlobal('location', { ...window.location, assign, origin: window.location.origin })
    auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 's@studio.in', displayName: 'S', role: 'STUDIO', mustChangePassword: false })
    render(<ClientsCard eventId="e1" eventName="Wedding" refreshKey={0} />)
    await user.type(await screen.findByLabelText('Client name'), 'Arun & Priya')
    await user.type(screen.getByLabelText('Client email'), 'couple@test.in')
    await user.type(screen.getByLabelText(/WhatsApp number/), '98765 43210')
    await user.click(screen.getByRole('button', { name: 'Create client login' }))
    await waitFor(() => expect(calls.find((c) => c.method === 'POST')?.body).toMatchObject({ name: 'Arun & Priya', email: 'couple@test.in', phone: '98765 43210' }))
    expect(await screen.findByTestId('client-credentials')).toHaveTextContent('couple@test.in')

    await user.click(screen.getByRole('button', { name: 'Download all 1 (.zip)' }))
    await waitFor(() => expect(assign).toHaveBeenCalledWith('http://localhost:8080/api/public/downloads/zip_x'))
    expect(calls.find((c) => c.path.endsWith('/downloads'))?.body).toEqual({ album: true })
    expect(await screen.findByText('Download complete ✓', {}, { timeout: 3000 })).toBeInTheDocument()
  })
})
