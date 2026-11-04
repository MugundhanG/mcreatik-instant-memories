import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { BillingSummary } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'

const billing: BillingSummary = {
  plan: 'MONTHLY', free: false, monthlyQuota: 4, usedThisMonth: 4, credits: 0, unitPrice: 999, monthlyPrice: 5000,
  upiId: 'mcreatik@okicici', payeeName: 'McreatiK Studios', payments: [], onlinePayments: false,
}

describe('Buy events by UPI', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    delete window.Razorpay
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

  it('only offers the UPI QR when online payment is not set up', () => {
    render(<BuyEventsDialog billing={billing} onPaid={vi.fn()} onClose={vi.fn()} />)
    expect(screen.queryByRole('tab', { name: /Card, netbanking/ })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Pay with UPI app' })).toBeInTheDocument()
  })
})

const order = {
  paymentId: 'p-1', razorpayOrderId: 'order_ABC123', keyId: 'rzp_test_key', amountPaise: 199800, currency: 'INR',
  events: 2, amount: 1998, studioName: 'Lens & Light', email: 'x@studio.com',
}

/** A stand-in for Razorpay Checkout: pays at once, or the studio closes it. */
function stubRazorpay(behaviour: 'pay' | 'close' | 'decline-then-close') {
  const seen: Record<string, unknown>[] = []
  window.Razorpay = class {
    private failed?: (r: { error?: { description?: string } }) => void
    constructor(private options: Record<string, unknown>) {
      seen.push(options)
    }
    on(_e: string, handler: (r: { error?: { description?: string } }) => void) {
      this.failed = handler
    }
    open() {
      const o = this.options as { handler: (r: unknown) => void; modal: { ondismiss: () => void } }
      if (behaviour === 'pay') o.handler({ razorpay_payment_id: 'pay_XYZ', razorpay_order_id: 'order_ABC123', razorpay_signature: 'sig' })
      else {
        if (behaviour === 'decline-then-close') this.failed?.({ error: { description: 'Your card was declined' } })
        o.modal.ondismiss()
      }
    }
  } as unknown as typeof window.Razorpay
  return seen
}

function mockServer() {
  const calls: { path: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input)).pathname
    calls.push({ path, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    if (path.endsWith('/online-orders')) return new Response(JSON.stringify(order), { status: 201, headers: { 'Content-Type': 'application/json' } })
    if (path.endsWith('/confirm')) return new Response(JSON.stringify({ paymentId: 'p-1', events: 2, credits: 2 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    return new Response('{}', { status: 404 })
  }))
  return calls
}

describe('Buy events online (card, netbanking, UPI, wallet)', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
    delete window.Razorpay
  })
  const online = { ...billing, onlinePayments: true }

  it('opens Razorpay for the server order, confirms the payment and carries on', async () => {
    const user = userEvent.setup()
    const calls = mockServer()
    const seen = stubRazorpay('pay')
    const onPaid = vi.fn()
    render(<BuyEventsDialog billing={online} onPaid={onPaid} onClose={vi.fn()} />)
    expect(screen.getByRole('tab', { name: /Card, netbanking/ })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('button', { name: 'More events' }))
    await user.click(screen.getByRole('button', { name: 'Pay ₹1,998 for 2 events' }))

    expect(await screen.findByText('Paid ✓')).toBeInTheDocument()
    expect(calls[0]).toEqual({ path: '/api/admin/billing/online-orders', body: { events: 2 } })
    expect(seen[0]).toMatchObject({ key: 'rzp_test_key', order_id: 'order_ABC123', amount: 199800, currency: 'INR' })
    expect(calls[1]).toEqual({ path: '/api/admin/billing/online-orders/p-1/confirm',
      body: { razorpayPaymentId: 'pay_XYZ', razorpaySignature: 'sig' } })
    await waitFor(() => expect(onPaid).toHaveBeenCalled(), { timeout: 3000 })
  })

  it('changes nothing when the studio closes the payment window', async () => {
    const user = userEvent.setup()
    const calls = mockServer()
    stubRazorpay('close')
    const onPaid = vi.fn()
    render(<BuyEventsDialog billing={online} onPaid={onPaid} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: /Pay ₹999 for 1 event/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Pay ₹999 for 1 event/ })).toBeEnabled())
    expect(calls.map((c) => c.path)).toEqual(['/api/admin/billing/online-orders'])
    expect(onPaid).not.toHaveBeenCalled()
  })

  it('explains a declined payment and still offers the UPI QR', async () => {
    const user = userEvent.setup()
    mockServer()
    stubRazorpay('decline-then-close')
    render(<BuyEventsDialog billing={online} onPaid={vi.fn()} onClose={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: /Pay ₹999 for 1 event/ }))
    expect(await screen.findByText(/Your card was declined\. No money was taken/)).toBeInTheDocument()
    await user.click(screen.getByRole('tab', { name: 'UPI QR' }))
    expect(screen.getByRole('link', { name: 'Pay with UPI app' })).toBeInTheDocument()
  })
})
