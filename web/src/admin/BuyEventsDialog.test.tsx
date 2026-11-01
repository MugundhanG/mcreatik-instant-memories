import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BillingSummary } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'

const billing: BillingSummary = {
  plan: 'MONTHLY', free: false, monthlyQuota: 4, usedThisMonth: 4, credits: 0, unitPrice: 999, monthlyPrice: 5000,
  upiId: 'mcreatik@okicici', payeeName: 'McreatiK Studios', payments: [],
}

describe('Buy events by UPI', () => {
  afterEach(() => vi.unstubAllGlobals())

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
    await user.click(screen.getByRole('button', { name: /I've paid ₹2,997: add 3 events/ }))
    await waitFor(() => expect(onPaid).toHaveBeenCalled())
    const [url, init] = fetchMock.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toMatch(/\/api\/admin\/billing\/payments$/)
    expect(JSON.parse(String(init.body))).toEqual({ events: 3, utr: '418299301122' })
  })

  it('says so when McreatiK has not set up a UPI ID yet', () => {
    render(<BuyEventsDialog billing={{ ...billing, upiId: null }} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.getByText(/Payments are not set up yet/)).toBeInTheDocument()
  })
})
