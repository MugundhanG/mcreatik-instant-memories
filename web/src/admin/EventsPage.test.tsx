import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AdminApp from './AdminApp'
import { auth } from './auth'

const ok = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })

function setUp(role: 'STUDIO' | 'SUPER_ADMIN') {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input)).pathname
    if (path === '/api/admin/account') return ok({ email: 'me@test', displayName: 'Lens & Light', role, mustChangePassword: false })
    if (path === '/api/admin/events') return ok([])
    if (path === '/api/admin/billing') return ok({ plan: 'UNLIMITED', free: true, monthlyQuota: 0, usedThisMonth: 0, credits: 0, unitPrice: 0, mainCredits: 0, quickCredits: 0, carryOver: 0, packActive: false, planPaidUntil: null, prices: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300 } })
    return ok(null)
  }))
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@test', displayName: 'Lens & Light', role, mustChangePassword: false })
  render(<MemoryRouter initialEntries={['/']}><AdminApp /></MemoryRouter>)
}

describe('Events page', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('walks a new studio through its first event', async () => {
    setUp('STUDIO')
    const first = await screen.findByTestId('first-event')
    expect(within(first).getAllByRole('listitem').map((li) => li.querySelector('p')?.textContent)).toEqual(['Create the event', 'Print the QR poster', 'Connect a camera'])
    expect(within(first).getByRole('link', { name: 'Create your first event' })).toHaveAttribute('href', '/admin/events/new')
  })

  it('has a phone menu with the same places as the top bar', async () => {
    const user = userEvent.setup()
    setUp('SUPER_ADMIN')
    await screen.findByTestId('first-event')
    await user.click(screen.getByRole('button', { name: 'Menu' }))
    const menu = document.getElementById('phone-menu')!
    expect(within(menu).getAllByRole('link').map((a) => a.textContent)).toEqual(['Events', 'Install uploader', 'Studios', 'Pricing & payments', 'Docs', 'Account · Lens & Light'])
    expect(within(menu).getByRole('button', { name: 'Sign out' })).toBeInTheDocument()
    await user.click(within(menu).getByRole('link', { name: 'Studios' }))
    // It fades out, then is gone.
    await waitFor(() => expect(document.getElementById('phone-menu')).toBeNull())
  })
})
