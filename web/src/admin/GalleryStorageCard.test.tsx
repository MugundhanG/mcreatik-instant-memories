import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BillingSummary, EventDetail } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'
import { GalleryStorageCard } from './GalleryStorageCard'

const GB = 1024 ** 3

const billing: BillingSummary = {
  plan: 'PER_EVENT', free: false, monthlyQuota: 0, usedThisMonth: 0, credits: 0, unitPrice: 2499, monthlyPrice: 0,
  upiId: 'mcreatik@okicici', payeeName: 'McreatiK', payments: [], onlinePayments: false,
  mainCredits: 0, quickCredits: 0, carryOver: 0, packActive: false, planPaidUntil: null, clientCredits: 0,
  prices: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300,
    storageGb: 15, freeStorageGb: 5, clientSetup: 250, activeMonths: 6 },
}

const event = (over: Partial<EventDetail> = {}): EventDetail => ({
  id: 'e1', name: 'Arun & Priya', slug: 'arun-priya', eventDate: '2026-11-01', startTime: null, endTime: null, status: 'LIVE',
  coverPhotoId: null, coverUrl: null, galleryUrl: 'https://live.test/e/arun-priya', retentionUntil: '2027-05-31',
  createdAt: '', updatedAt: '', ownerId: 's1', ownerName: null, eventType: 'MAIN', activeUntil: '2027-05-01',
  storageQuotaGb: 5, storageBytes: 0, storageLocked: false, expired: false, ...over,
})

describe('Gallery & storage', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows use of the included storage, how long it stays open, and what keeping it longer costs', () => {
    render(<GalleryStorageCard event={event()} billing={billing} bytes={2.5 * GB} onChanged={vi.fn()} />)
    expect(screen.getByTestId('storage-card')).toHaveTextContent('2.5 GB used of 5 GB')
    expect(screen.getByRole('progressbar', { name: 'Storage used' })).toHaveAttribute('aria-valuenow', '50')
    expect(screen.getByTestId('active-until')).toHaveTextContent('Open until 1 May 2027')
    expect(screen.getByRole('button', { name: 'Keep 6 more months · ₹45' })).toBeInTheDocument()
    expect(screen.queryByTestId('storage-over')).not.toBeInTheDocument()
  })

  it('tells a live event over its free storage the GB, price and date, and a locked one that the couple waits', () => {
    const { rerender } = render(<GalleryStorageCard event={event()} billing={billing} bytes={8.2 * GB} onChanged={vi.fn()} />)
    expect(screen.getByTestId('storage-over')).toHaveTextContent('Add 4 GB (₹60) by 15 November 2026')
    expect(screen.getByTestId('storage-over')).toHaveTextContent('Uploads are never stopped')
    rerender(<GalleryStorageCard event={event({ storageLocked: true })} billing={billing} bytes={8.2 * GB} onChanged={vi.fn()} />)
    expect(screen.getByTestId('storage-over')).toHaveTextContent("The couple's gallery is paused")
  })

  it('asks a new client gallery for storage, and says when a gallery has closed', () => {
    const { rerender } = render(<GalleryStorageCard event={event({ eventType: 'CLIENT', storageQuotaGb: 0 })} billing={billing} bytes={0} onChanged={vi.fn()} />)
    expect(screen.getByText('Add storage to start uploading photos to this client gallery.')).toBeInTheDocument()
    rerender(<GalleryStorageCard event={event({ expired: true })} billing={billing} bytes={GB} onChanged={vi.fn()} />)
    expect(screen.getByTestId('active-until')).toHaveTextContent('Closed on 1 May 2027')
    expect(screen.getByTestId('active-until')).toHaveTextContent('Deleted for good on 31 May 2027')
  })

  it('has no limits or prices for McreatiK and unlimited studios', () => {
    render(<GalleryStorageCard event={event()} billing={{ ...billing, free: true }} bytes={20 * GB} onChanged={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Add storage' })).not.toBeInTheDocument()
    expect(screen.queryByTestId('storage-over')).not.toBeInTheDocument()
  })
})

describe('Buying storage, client galleries and 6 more months', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('prices storage packs per GB with a photo estimate and pays for exactly what was chosen', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ id: 'p' }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    render(<BuyEventsDialog billing={billing} purchase={{ item: 'STORAGE', eventId: 'e1', quantity: 10 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Add storage' })).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹150')
    await user.click(screen.getByRole('radio', { name: /25 GB/ }))
    expect(screen.getByRole('radio', { name: /25 GB/ })).toHaveTextContent('≈ 2,050')
    expect(screen.getByTestId('amount')).toHaveTextContent('₹375')
    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    await user.click(screen.getByRole('button', { name: "I've paid ₹375" }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(JSON.parse(String((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body)))
      .toEqual({ item: 'STORAGE', eventId: 'e1', quantity: 25, utr: '418299301122' })
  })

  it('sells client galleries at the setup price, and 6 more months at the GB the gallery holds', () => {
    const { unmount } = render(<BuyEventsDialog billing={billing} purchase={{ item: 'CLIENT_SETUP', quantity: 2 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Client galleries' })).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹500')
    expect(screen.getByTestId('event-count')).toHaveTextContent('2 galleries')
    unmount()
    render(<BuyEventsDialog billing={billing} purchase={{ item: 'EXTEND', eventId: 'e1', quantity: 3 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Keep 6 more months' })).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹45')
    expect(screen.getByText('6 more months (3 GB)')).toBeInTheDocument()
  })
})
