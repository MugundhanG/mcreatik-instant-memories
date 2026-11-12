import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BillingSummary } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'

const billing: BillingSummary = {
  plan: 'MONTHLY', free: false, monthlyQuota: 4, usedThisMonth: 4, credits: 0, unitPrice: 999, quickUnitPrice: 1499, monthlyPrice: 5000,
  upiId: 'mcreatik@okicici', payeeName: 'McreatiK Studios', payments: [],
  mainCredits: 0, quickCredits: 0, carryOver: 0, packActive: true, planPaidUntil: null,
  prices: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300, storageGb: 15, freeStorageGb: 5, clientSetup: 250, activeMonths: 3,
    main2Extra: 1499, main4Extra: 999, quick2Extra: 999, quick4Extra: 799, quickPackMain: 1499 },
  clientCredits: 0,
}

describe('Buy events by UPI', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('prices the chosen number of events, links the UPI app and reports the transaction ID', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ id: 'pay1' }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const onPaid = vi.fn()
    render(<BuyEventsDialog billing={billing} reason="You have used your 4 events for this month." onPaid={onPaid} onClose={vi.fn()} />)

    expect(screen.getByText('You have used your 4 events for this month.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'More events' }))
    await user.click(screen.getByRole('button', { name: 'More events' }))
    expect(screen.getByTestId('amount')).toHaveTextContent('₹2,997')
    const pay = new URL(screen.getByRole('link', { name: 'Pay with UPI app' }).getAttribute('href')!)
    expect(pay.protocol).toBe('upi:')
    expect(Object.fromEntries(pay.searchParams)).toMatchObject({ pa: 'mcreatik@okicici', am: '2997', cu: 'INR' })

    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    await user.click(screen.getByRole('button', { name: "I've paid ₹2,997" }))
    await waitFor(() => expect(onPaid).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toMatch(/\/api\/admin\/billing\/payments$/)
    expect(JSON.parse(String(init.body))).toEqual({ item: 'MAIN_EVENT', quantity: 3, utr: '418299301122' })
  })

  it('switches between Main and Quick events, and pays for a pack or an upgrade at the server prices', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ id: 'pay1' }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)
    const perEvent = { ...billing, plan: 'PER_EVENT' as const, unitPrice: 2499 }
    const { unmount } = render(<BuyEventsDialog billing={perEvent} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByTestId('amount')).toHaveTextContent('₹2,499')
    await user.click(screen.getByRole('radio', { name: /Quick/ }))
    await user.click(screen.getByRole('button', { name: 'More events' }))
    expect(screen.getByTestId('amount')).toHaveTextContent('₹2,998')
    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    await user.click(screen.getByRole('button', { name: "I've paid ₹2,998" }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(JSON.parse(String((fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1].body)))
      .toEqual({ item: 'QUICK_EVENT', quantity: 2, utr: '418299301122' })
    unmount()

    const pack = render(<BuyEventsDialog billing={perEvent} purchase={{ item: 'PLAN', plan: 'MAIN_4' }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Monthly pack' })).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹4,999')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Pack' }), 'QUICK_2')
    await user.click(screen.getByRole('button', { name: 'More months' }))
    expect(screen.getByTestId('amount')).toHaveTextContent('₹4,998')
    pack.unmount()

    render(<BuyEventsDialog billing={perEvent} purchase={{ item: 'UPGRADE', eventId: 'e-9' }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Upgrade to a Main event' })).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹1,000')
    expect(screen.queryByRole('button', { name: 'More events' })).not.toBeInTheDocument()
  })

  it('prices extra events at the pack price while a Main pack is paid up', async () => {
    const user = userEvent.setup()
    const main4 = { ...billing, plan: 'MAIN_4' as const, unitPrice: 999, quickUnitPrice: 999 }
    render(<BuyEventsDialog billing={main4} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText(/Extra events at your pack's price: Main ₹999, Quick ₹999 \(normally ₹2,499 and ₹1,499\)/)).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹999')
    await user.click(screen.getByRole('radio', { name: /Quick/ }))
    expect(screen.getByRole('radio', { name: /Quick/ })).toHaveTextContent('₹999')
    expect(screen.getByTestId('amount')).toHaveTextContent('₹999')
  })

  const paid = () => vi.fn(async () => new Response(JSON.stringify({ id: 'pay1' }), { status: 201, headers: { 'Content-Type': 'application/json' } }))
  const sentBody = (f: ReturnType<typeof vi.fn>) => JSON.parse(String((f.mock.calls[0] as unknown as [string, RequestInit])[1].body))

  it('adds storage and months to a client gallery in the same payment', async () => {
    const user = userEvent.setup()
    const fetchMock = paid()
    vi.stubGlobal('fetch', fetchMock)
    render(<BuyEventsDialog billing={billing} purchase={{ item: 'CLIENT_SETUP', quantity: 2 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByTestId('amount')).toHaveTextContent('₹500') // 2 galleries, storage later
    await user.click(screen.getByRole('radio', { name: /25 GB/ }))
    // One gallery for 1 month (the default) with its storage: ₹250 + 25 GB × ₹15 × 1.
    expect(screen.getByTestId('breakdown')).toHaveTextContent('1 × ₹250 + 25 GB × ₹15 × 1 month')
    expect(screen.getByTestId('amount')).toHaveTextContent('₹625')
    await user.click(screen.getByRole('button', { name: 'More months' }))
    await user.click(screen.getByRole('button', { name: 'More months' }))
    expect(screen.getByTestId('amount')).toHaveTextContent('₹1,375') // ₹250 + 25 GB × ₹15 × 3 months
    expect(screen.queryByRole('button', { name: 'More galleries' })).not.toBeInTheDocument()
    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    await user.click(screen.getByRole('button', { name: "I've paid ₹1,375" }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(sentBody(fetchMock)).toEqual({ item: 'CLIENT_SETUP', quantity: 1, storageGb: 25, months: 3, utr: '418299301122' })
  })

  it('never goes below 1 month, and a custom storage size must be at least 5 GB', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', paid())
    render(<BuyEventsDialog billing={billing} purchase={{ item: 'CLIENT_SETUP', quantity: 1 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Fewer months' }))
    expect(screen.getByTestId('months-count')).toHaveTextContent('1 month')
    const other = screen.getByRole('spinbutton', { name: /other size/ })
    await user.type(other, '3')
    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    expect(screen.getByRole('button', { name: /I've paid/ })).toBeDisabled()
    await user.clear(other)
    await user.type(other, '12')
    expect(screen.getByTestId('amount')).toHaveTextContent('₹430') // ₹250 + 12 GB × ₹15 × 1
    expect(screen.getByRole('button', { name: /I've paid/ })).toBeEnabled()
  })

  it('buys storage in presets or a custom size from 5 GB, priced for the months the gallery has left', async () => {
    const user = userEvent.setup()
    const fetchMock = paid()
    vi.stubGlobal('fetch', fetchMock)
    render(<BuyEventsDialog billing={billing} purchase={{ item: 'STORAGE', eventId: 'e-1', months: 2, quantity: 10 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.queryByRole('radio', { name: /100 GB/ })).not.toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹300') // 10 GB × ₹15 × 2 months
    await user.type(screen.getByRole('spinbutton', { name: /other size/ }), '40')
    expect(screen.getByTestId('amount')).toHaveTextContent('₹1,200')
    await user.clear(screen.getByRole('spinbutton', { name: /other size/ }))
    await user.type(screen.getByRole('spinbutton', { name: /other size/ }), '4')
    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    expect(screen.getByRole('button', { name: /I've paid/ })).toBeDisabled()
    await user.click(screen.getByRole('radio', { name: '5 GB' }))
    await user.click(screen.getByRole('button', { name: "I've paid ₹150" }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(sentBody(fetchMock)).toMatchObject({ item: 'STORAGE', eventId: 'e-1', quantity: 5 })
  })

  it('keeps a gallery open for the chosen months, charged on all its storage', async () => {
    const user = userEvent.setup()
    const fetchMock = paid()
    vi.stubGlobal('fetch', fetchMock)
    render(<BuyEventsDialog billing={billing} purchase={{ item: 'EXTEND', eventId: 'e-1', quantity: 5, months: 3 }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByRole('dialog', { name: 'Keep more months' })).toBeInTheDocument()
    expect(screen.getByText(/5 GB × 3 months included with every Main or Quick event are already covered/)).toBeInTheDocument()
    expect(screen.getByTestId('amount')).toHaveTextContent('₹225') // 5 GB × ₹15 × 3
    await user.click(screen.getByRole('button', { name: 'Fewer months' }))
    await user.click(screen.getByRole('button', { name: 'Fewer months' }))
    await user.click(screen.getByRole('button', { name: 'Fewer months' }))
    expect(screen.getByTestId('months-count')).toHaveTextContent('1 month')
    expect(screen.getByTestId('amount')).toHaveTextContent('₹75')
    await user.type(screen.getByPlaceholderText(/418299301122/), '418299301122')
    await user.click(screen.getByRole('button', { name: "I've paid ₹75" }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(sentBody(fetchMock)).toEqual({ item: 'EXTEND', eventId: 'e-1', quantity: 5, months: 1, utr: '418299301122' })
  })

  it('says so when McreatiK has not set up a UPI ID yet', () => {
    render(<BuyEventsDialog billing={{ ...billing, upiId: null }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText(/Payments are not set up yet/)).toBeInTheDocument()
  })

  it('has one way to pay: the UPI QR and the transaction ID', () => {
    render(<BuyEventsDialog billing={billing} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pay with UPI app' })).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/razorpay|card, netbanking/i)
  })
})
