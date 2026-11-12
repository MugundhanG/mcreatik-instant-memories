import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ClientApp from './ClientApp'
import { clientSession } from './clientApi'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('Client login', () => {
  afterEach(() => {
    clientSession.signOut()
    vi.unstubAllGlobals()
  })

  it('sends the event address from the couple\'s link', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (_u: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)))
      return json({ message: 'no' }, 401)
    }))
    render(<MemoryRouter initialEntries={['/client/login?event=arun-priya-7k3d']}><Routes><Route path="/client/*" element={<ClientApp />} /></Routes></MemoryRouter>)
    await user.type(await screen.findByLabelText('Email'), 'a@b.in')
    await user.type(screen.getByLabelText('Password'), 'secret-123')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    await waitFor(() => expect(bodies.length).toBe(1))
    expect(bodies[0]).toEqual({ email: 'a@b.in', password: 'secret-123', eventSlug: 'arun-priya-7k3d' })
  })

  it('asks which gallery when the email has several, then logs in to the chosen one', async () => {
    const user = userEvent.setup()
    const bodies: Record<string, unknown>[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input)).pathname
      if (path !== '/api/client/auth/login') return json({}, 404)
      const body = JSON.parse(String(init?.body)) as Record<string, unknown>
      bodies.push(body)
      if (!body.eventSlug) {
        return json({ code: 'CHOOSE_EVENT', message: 'Choose one.', events: [
          { slug: 'engagement-1', name: 'Engagement', eventDate: '2026-10-01' }, { slug: 'wedding-2', name: 'Wedding', eventDate: '2026-12-01' }] }, 409)
      }
      return json({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), name: 'Arun', mustChangePassword: false })
    }))
    render(<MemoryRouter initialEntries={['/client/login']}><Routes><Route path="/client/*" element={<ClientApp />} /></Routes></MemoryRouter>)
    await user.type(await screen.findByLabelText('Email'), 'a@b.in')
    await user.type(screen.getByLabelText('Password'), 'secret-123')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByText('Which gallery?')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Wedding/ }))
    await waitFor(() => expect(bodies.length).toBe(2))
    expect(bodies[1]).toEqual({ email: 'a@b.in', password: 'secret-123', eventSlug: 'wedding-2' })
  })
})
