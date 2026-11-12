import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { auth } from './auth'
import NewEventPage from './NewEventPage'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

const billing = {
  plan: 'PER_EVENT', free: false, monthlyQuota: 0, usedThisMonth: 0, credits: 0, unitPrice: 2499, quickUnitPrice: 1499, monthlyPrice: 0,
  upiId: 'mcreatik@okicici', payeeName: 'McreatiK', payments: [], mainCredits: 1, quickCredits: 0, carryOver: 0, packActive: false,
  planPaidUntil: null, clientCredits: 0,
  prices: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300, storageGb: 15,
    freeStorageGb: 5, clientSetup: 250, activeMonths: 3, main2Extra: 1499, main4Extra: 999, quick2Extra: 999, quick4Extra: 799, quickPackMain: 1499 },
}

function setUp() {
  const calls: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input)).pathname
    calls.push({ path, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    if (path === '/api/admin/billing') return json(billing)
    if (path === '/api/admin/events' && init?.method === 'POST') return json({ id: 'ev-1' }, 201)
    return json(null)
  }))
  auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'me@test', displayName: 'Lens', role: 'STUDIO', mustChangePassword: false })
  render(
    <MemoryRouter initialEntries={['/admin/events/new']}>
      <Routes>
        <Route path="/admin/events/new" element={<NewEventPage />} />
        <Route path="/admin/events/:id" element={<p>Event page</p>} />
      </Routes>
    </MemoryRouter>,
  )
  return calls
}

describe('New event: how long the galleries stay open', () => {
  afterEach(() => {
    auth.signOut()
    vi.unstubAllGlobals()
  })

  it('includes 3 months for Main events, prices extra months and offers Change storage & months right after creating', async () => {
    const user = userEvent.setup()
    const calls = setUp()
    const months = await screen.findByRole('combobox', { name: 'Gallery open for' })
    expect(months).toHaveValue('3')
    expect(screen.getByRole('option', { name: '3 months (included)' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: '2 months' })).not.toBeInTheDocument()
    await user.selectOptions(months, '5')
    expect(screen.getByTestId('extra-months')).toHaveTextContent('Extra 2 months: ₹150') // 5 GB × ₹15 × 2
    await user.type(screen.getByLabelText(/Event name/), 'Arun & Priya')
    await user.click(screen.getByRole('button', { name: 'Create event' }))
    const dialog = await screen.findByRole('dialog', { name: 'Change storage & months' })
    expect(dialog).toHaveTextContent('₹150')
    expect(calls.some((c) => c.path === '/api/admin/events' && c.body)).toBe(true)
  })

  it('goes straight to the event when the included months are kept', async () => {
    const user = userEvent.setup()
    setUp()
    await screen.findByRole('combobox', { name: 'Gallery open for' })
    await user.type(screen.getByLabelText(/Event name/), 'Arun & Priya')
    await user.click(screen.getByRole('button', { name: 'Create event' }))
    expect(await screen.findByText('Event page')).toBeInTheDocument()
  })

  it('starts a client gallery at 1 month, and its months go into the purchase', async () => {
    const user = userEvent.setup()
    setUp()
    await user.click(await screen.findByRole('radio', { name: /Client gallery only/ }))
    const months = screen.getByRole('combobox', { name: 'Gallery open for' })
    expect(months).toHaveValue('1')
    await user.selectOptions(months, '4')
    await waitFor(() => expect(months).toHaveValue('4'))
  })
})
