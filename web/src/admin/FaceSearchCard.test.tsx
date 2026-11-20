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
      if (init?.method === 'PUT') current = { ...current, ...JSON.parse(String(init.body)) }
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
    // Not bought yet: both switches are there but locked.
    expect(screen.getByRole('switch', { name: 'Find my photos' })).toBeDisabled()
    expect(screen.getByRole('switch', { name: 'Private gallery' })).toBeDisabled()
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

  it('lets the super admin switch it on for a free trial', async () => {
    const fn = serve(status())
    render(<FaceSearchCard event={event()} billing={billing} superAdmin />)
    await userEvent.click(await screen.findByRole('switch', { name: 'Find my photos' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Turn on' }))
    await waitFor(() => expect(screen.getByTestId('face-search-state')).toHaveTextContent('On'))
    const put = fn.mock.calls.find(([, init]) => init?.method === 'PUT')!
    expect(JSON.parse(String(put[1]!.body))).toEqual({ enabled: true })
  })

  it('lets a studio that paid switch face search and the private gallery itself', async () => {
    const fn = serve(status({ enabled: true, paid: true, indexed: 10, total: 10 }))
    render(<FaceSearchCard event={event()} billing={billing} superAdmin={false} />)
    const privateSwitch = await screen.findByRole('switch', { name: 'Private gallery' })
    expect(privateSwitch).toBeEnabled()
    await userEvent.click(privateSwitch)
    await userEvent.click(await screen.findByRole('button', { name: 'Make private' }))
    await waitFor(() => expect(screen.getByTestId('private-gallery-state')).toHaveTextContent('On'))
    const puts = () => fn.mock.calls.filter(([, init]) => init?.method === 'PUT').map(([, init]) => JSON.parse(String(init!.body)))
    expect(puts()).toEqual([{ privateGallery: true }])

    await userEvent.click(screen.getByRole('switch', { name: 'Find my photos' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Turn off' }))
    await waitFor(() => expect(screen.getByTestId('face-search-state')).toHaveTextContent('Off'))
    expect(puts()).toEqual([{ privateGallery: true }, { enabled: false }])
  })

  it('shows why scanning is paused when AWS refuses', async () => {
    serve(status({ enabled: true, paid: true, total: 53, problem: 'is not authorized to perform: rekognition:CreateCollection' }))
    render(<FaceSearchCard event={event()} billing={billing} superAdmin />)
    const note = await screen.findByTestId('face-search-problem')
    expect(note).toHaveTextContent('Face scanning is paused.')
    expect(note).toHaveTextContent('rekognition:CreateCollection')
    expect(note).toHaveTextContent('face-search-check.sh')
  })

  it('keeps the private gallery locked while face search is off', async () => {
    serve(status({ paid: true }))
    render(<FaceSearchCard event={event()} billing={billing} superAdmin={false} />)
    expect(await screen.findByRole('switch', { name: 'Private gallery' })).toBeDisabled()
    expect(screen.getByText('Needs Find my photos on.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Add face search/ })).not.toBeInTheDocument() // paid already
  })

  it('is not shown for a client gallery', async () => {
    const fn = serve(status())
    const { container } = render(<FaceSearchCard event={event({ eventType: 'CLIENT' })} billing={billing} superAdmin={false} />)
    await waitFor(() => expect(fn).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
