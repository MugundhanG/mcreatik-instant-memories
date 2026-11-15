import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BillingSummary, EventDetail, FaceSearchStatus } from '../lib/types'
import { FaceSearchCard } from './FaceSearchCard'

const billing: BillingSummary = {
  plan: 'PER_EVENT', free: false, monthlyQuota: 0, usedThisMonth: 0, credits: 0, unitPrice: 2499, quickUnitPrice: 1499, monthlyPrice: 0,
  upiId: 'mcreatik@okicici', payeeName: 'McreatiK', payments: [],
  mainCredits: 0, quickCredits: 0, carryOver: 0, packActive: false, planPaidUntil: null, clientCredits: 0,
  prices: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300,
    storageGb: 15, freeStorageGb: 5, clientSetup: 250, activeMonths: 3,
    main2Extra: 1499, main4Extra: 999, quick2Extra: 999, quick4Extra: 799, quickPackMain: 1499 },
}
const event = (over: Partial<EventDetail> = {}): EventDetail => ({
  id: 'e1', name: 'Arun & Priya', slug: 'arun-priya', eventDate: '2026-11-01', startTime: null, endTime: null, status: 'LIVE',
  coverPhotoId: null, coverUrl: null, galleryUrl: 'https://live.test/e/arun-priya', retentionUntil: '2027-05-31',
  createdAt: '', updatedAt: '', ownerId: 's1', ownerName: null, eventType: 'MAIN', ...over,
})
const status = (over: Partial<FaceSearchStatus> = {}): FaceSearchStatus => ({ enabled: false, available: true, indexed: 0, total: 0, mainPrice: 999, quickPrice: 499, ...over })

function serve(initial: FaceSearchStatus) {
  let current = initial
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/face-search')) {
      if (init?.method === 'PUT') current = { ...current, enabled: JSON.parse(String(init.body)).enabled }
      return new Response(JSON.stringify(current), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response('{}', { status: 404 })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('Face search card', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('offers the add-on with its price when off', async () => {
    serve(status())
    render(<FaceSearchCard event={event()} billing={billing} superAdmin={false} />)
    expect(await screen.findByRole('button', { name: 'Add face search — ₹999' })).toBeInTheDocument()
    expect(screen.getByTestId('face-search-state')).toHaveTextContent('Off')
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Add face search/ }))
    expect(screen.getByRole('dialog', { name: 'Find my photos' })).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹999')
  })

  it('uses the Quick price for a Quick event', async () => {
    serve(status())
    render(<FaceSearchCard event={event({ eventType: 'QUICK' })} billing={billing} superAdmin={false} />)
    expect(await screen.findByRole('button', { name: 'Add face search — ₹499' })).toBeInTheDocument()
  })

  it('shows how many photos have been scanned when on', async () => {
    serve(status({ enabled: true, indexed: 120, total: 300 }))
    render(<FaceSearchCard event={event()} billing={billing} superAdmin={false} />)
    expect(await screen.findByText('Faces indexed 120 of 300')).toBeInTheDocument()
    expect(screen.getByTestId('face-search-state')).toHaveTextContent('On')
    expect(screen.getByRole('progressbar', { name: 'Photos scanned for faces' })).toHaveAttribute('aria-valuenow', '40')
    expect(screen.queryByRole('button', { name: /Add face search/ })).not.toBeInTheDocument()
  })

  it('says coming soon to studios, and that the flag is off to the super admin, when unavailable', async () => {
    serve(status({ available: false }))
    const { unmount } = render(<FaceSearchCard event={event()} billing={billing} superAdmin={false} />)
    expect(await screen.findByTestId('face-search-unavailable')).toHaveTextContent('Coming soon')
    expect(screen.queryByRole('button', { name: /Add face search/ })).not.toBeInTheDocument()
    unmount()
    render(<FaceSearchCard event={event()} billing={billing} superAdmin />)
    expect(await screen.findByText('Server feature flag is off.')).toBeInTheDocument()
  })

  it('lets only the super admin switch it on and off', async () => {
    const fn = serve(status())
    render(<FaceSearchCard event={event()} billing={billing} superAdmin />)
    const toggle = await screen.findByRole('switch', { name: 'Face search for this event' })
    await userEvent.click(toggle)
    await waitFor(() => expect(screen.getByTestId('face-search-state')).toHaveTextContent('On'))
    const put = fn.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(String(put[1]!.body))).toEqual({ enabled: true })
  })

  it('is not shown for a client gallery', async () => {
    const fn = serve(status())
    const { container } = render(<FaceSearchCard event={event({ eventType: 'CLIENT' })} billing={billing} superAdmin={false} />)
    await waitFor(() => expect(fn).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
