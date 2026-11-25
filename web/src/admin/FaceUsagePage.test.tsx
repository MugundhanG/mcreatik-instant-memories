import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { auth } from './auth'
import FaceUsagePage from './FaceUsagePage'

const usage = {
  from: '2026-11-01', to: '2026-11-25',
  events: [
    { eventId: 'a', eventName: 'Arun & Priya', studioName: 'Lens & Light', eventType: 'MAIN', eventDate: '2026-11-02', paid: true, photosScanned: 1000, facesIndexed: 3000, searches: 200, firstDay: '2026-11-02', lastDay: '2026-11-10' },
    { eventId: 'b', eventName: null, studioName: 'Frame Stories', eventType: null, eventDate: null, paid: false, photosScanned: 400, facesIndexed: 0, searches: 100, firstDay: null, lastDay: null },
    { eventId: 'c', eventName: 'Baby shower', studioName: 'Lens & Light', eventType: 'QUICK', eventDate: '2026-11-05', paid: true, photosScanned: 100, facesIndexed: 300, searches: 20, firstDay: null, lastDay: null },
  ],
  days: [{ day: '2026-11-02', photosScanned: 1500, facesIndexed: 3300, searches: 320 }],
}
const billing = { plan: 'PER_EVENT', free: true, packActive: false, prices: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, faceSearchMain: 499, faceSearchQuick: 299, facePackMain4: 1000, facePackQuick4: 1000 } }

function serve() {
  const fn = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    const body = url.includes('/api/admin/face-usage') ? usage : url.includes('/api/admin/billing') ? billing : {}
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}
const signIn = (role: 'SUPER_ADMIN' | 'STUDIO') => auth.signIn({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), email: 'x@y.in', displayName: 'X', role, mustChangePassword: false })
const renderPage = () => render(<MemoryRouter initialEntries={['/admin/face-usage']}><Routes><Route path="/admin/face-usage" element={<FaceUsagePage />} /><Route path="/admin" element={<p>Events home</p>} /></Routes></MemoryRouter>)

describe('Face search usage (McreatiK)', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => {
    vi.unstubAllGlobals()
    auth.signOut()
  })

  it('shows totals, per-type averages with profit or loss, the formula and the events', async () => {
    signIn('SUPER_ADMIN')
    const fn = serve()
    renderPage()
    const totals = await screen.findByTestId('face-usage-totals')
    expect(totals).toHaveTextContent('1,500') // photos scanned
    expect(totals).toHaveTextContent('320') // searches
    expect(totals).toHaveTextContent('3,300') // faces
    // ((1500 + 320) x 0.001 + 3300 x 0.00001) x 90 x 1.18 = ₹197
    expect(screen.getByTestId('total-Estimated cost')).toHaveTextContent('₹197')
    expect(screen.getByTestId('estimate-note')).toHaveTextContent('Estimate from AWS list prices; your AWS bill is the real figure. Face storage is counted as one month.')
    // One Main event: (1200 x 0.001 + 3000 x 0.00001) x 106.2 = ₹131; we charge ₹499 one by one, ₹250 a pack share.
    const main = screen.getByTestId('avg-MAIN')
    expect(main).toHaveTextContent('₹131')
    expect(main).toHaveTextContent('₹499 one by one')
    expect(within(main).getAllByText(/profit/)[0]).toHaveClass('text-emerald-700')
    expect(main).toHaveTextContent('₹1,000 ÷ 4 = ₹250')
    expect(screen.getByTestId('avg-QUICK')).toHaveTextContent('₹299 one by one')
    // Highest estimated cost first; a deleted event is still listed.
    const rows = within(screen.getByTestId('face-usage-table')).getAllByRole('row').slice(1)
    expect(rows[0]).toHaveTextContent('Arun & Priya')
    expect(screen.getByText('Deleted event')).toBeInTheDocument()
    expect(rows[1]).toHaveTextContent('No · free trial')
    const [from, to] = [new URL(String(fn.mock.calls[0][0])).searchParams.get('from'), new URL(String(fn.mock.calls[0][0])).searchParams.get('to')]
    expect(from).toMatch(/^\d{4}-\d{2}-01$/)
    expect(to).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('recalculates from the rates and sorts the other way on request', async () => {
    signIn('SUPER_ADMIN')
    serve()
    renderPage()
    await screen.findByTestId('face-usage-totals')
    const perCall = screen.getByRole('textbox', { name: 'US$ per image or search call' })
    await userEvent.clear(perCall)
    await userEvent.type(perCall, '0.002')
    expect(screen.getByTestId('total-Estimated cost')).toHaveTextContent('₹390') // ((1820 x 0.002) + 0.033) x 106.2
    expect(JSON.parse(localStorage.getItem('mk-face-usage-rates')!).perCall).toBe(0.002)
    await userEvent.click(screen.getByRole('button', { name: /Est\. ₹/ }))
    const rows = within(screen.getByTestId('face-usage-table')).getAllByRole('row').slice(1)
    expect(rows[0]).toHaveTextContent('Baby shower')
  })

  it('says so when nothing was used', async () => {
    signIn('SUPER_ADMIN')
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ from: 'a', to: 'b', events: [], days: [] }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    renderPage()
    expect(await screen.findByText('No face search usage in this period yet.')).toBeInTheDocument()
  })

  it('sends a studio back to its events without asking the server', () => {
    signIn('STUDIO')
    const fn = serve()
    renderPage()
    expect(screen.getByText('Events home')).toBeInTheDocument()
    expect(fn).not.toHaveBeenCalled()
  })
})
