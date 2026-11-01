import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ClientApp from './ClientApp'
import { clientSession } from './clientApi'

const me = {
  name: 'Arun & Priya', email: 'couple@test.in', mustChangePassword: false,
  event: { name: 'Arun & Priya Wedding', eventDate: '2026-11-20', coverUrl: null, brand: { name: 'Lens & Light', logoUrl: null, instagram: null, mcreatik: false } },
  albumLimit: 2, selectedCount: 1, totalPhotos: 3, uncategorizedPhotos: 1,
  categories: [{ id: 'cake', name: 'Cake cutting', position: 1, photos: 2 }, { id: 'empty', name: 'Rituals', position: 2, photos: 0 }],
}
const photo = (n: number, selected = false) => ({
  id: `p${n}`, thumbnailUrl: `https://media.test/t/${n}.jpg`, webUrl: `https://media.test/w/${n}.jpg`, width: 1200, height: 800,
  originalAvailable: true, selected, categoryId: n < 3 ? 'cake' : null,
})

function mockApi() {
  const calls: { method: string; url: URL; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input))
    const method = init?.method ?? 'GET'
    calls.push({ method, url, body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined })
    const ok = (d: unknown) => new Response(JSON.stringify(d), { status: 200, headers: { 'Content-Type': 'application/json' } })
    if (url.pathname === '/api/client/me') return ok(me)
    if (url.pathname === '/api/client/photos') {
      const cat = url.searchParams.get('category')
      const all = [photo(1, true), photo(2), photo(3)]
      return ok({ photos: cat ? all.filter((p) => p.categoryId === cat) : all, nextOffset: null })
    }
    if (method === 'PUT') return ok({ selectedCount: 2, albumLimit: 2 })
    return new Response('{}', { status: 404 })
  }))
  return calls
}

const renderPortal = () => render(
  <MemoryRouter initialEntries={['/client']}>
    <Routes><Route path="/client/*" element={<ClientApp />} /></Routes>
  </MemoryRouter>,
)

describe('Client portal', () => {
  afterEach(() => {
    clientSession.signOut()
    vi.unstubAllGlobals()
  })

  it('asks the couple to log in first', async () => {
    renderPortal()
    expect(await screen.findByRole('button', { name: 'Log in' })).toBeInTheDocument()
  })

  it('shows photos by category and selects them for the album within the limit', async () => {
    const user = userEvent.setup()
    const calls = mockApi()
    clientSession.set({ token: 't', expiresAt: new Date(Date.now() + 3_600_000).toISOString(), name: 'Arun & Priya', mustChangePassword: false })
    renderPortal()

    expect(await screen.findByRole('heading', { name: 'Arun & Priya Wedding' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Categories' })
    // Empty categories are not shown; unsorted photos sit under "More".
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['All 3', 'Cake cutting 2', 'More 1', '✓ My album 1'])
    await waitFor(() => expect(within(screen.getByTestId('client-photos')).getAllByRole('listitem')).toHaveLength(3))
    expect(screen.getByTestId('album-counter')).toHaveTextContent('1 / 2 selected')

    await user.click(within(nav).getByRole('button', { name: /Cake cutting/ }))
    await waitFor(() => expect(within(screen.getByTestId('client-photos')).getAllByRole('listitem')).toHaveLength(2))
    expect(calls.at(-1)!.url.searchParams.get('category')).toBe('cake')

    const add = screen.getAllByRole('button', { name: 'Add to album' })[0]
    await user.click(add)
    await waitFor(() => expect(screen.getByTestId('album-counter')).toHaveTextContent('2 / 2 selected'))
    expect(calls.find((c) => c.method === 'PUT')?.body).toEqual({ selected: true })
    // The album is full: no more photos can be added until one is removed.
    expect(screen.queryAllByRole('button', { name: 'Add to album' }).every((b) => (b as HTMLButtonElement).disabled)).toBe(true)
  })
})
