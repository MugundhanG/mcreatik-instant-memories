import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ContactPage from './ContactPage'
import PricingPage from './PricingPage'
import RefundsPage from './RefundsPage'
import TermsPage from './TermsPage'

describe('Policy pages', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the current Main / Quick prices and the monthly packs from the server', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      perEventPrice: 2499, monthlyPrice: 5000, monthlyEvents: 4, extraEventPrice: 999, currency: 'INR',
      packages: { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300,
        storageGb: 15, freeStorageGb: 5, clientSetup: 250, activeMonths: 3,
    main2Extra: 1499, main4Extra: 999, quick2Extra: 999, quick4Extra: 799 },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<PricingPage />)
    expect((await screen.findAllByText('₹2,499')).length).toBeGreaterThan(0)
    expect(screen.getByText('Main event').parentElement).toHaveTextContent('₹2,499')
    expect(screen.getByText('Quick event').parentElement).toHaveTextContent('₹1,499')
    expect(screen.getByText(/Upgrade it to Main for ₹1,000/)).toBeInTheDocument()
    const main4 = screen.getByRole('row', { name: /Main · 4 events a month/ })
    expect(main4).toHaveTextContent('₹4,999')
    expect(main4).toHaveTextContent('₹1,250')
    expect(main4).toHaveTextContent('₹999') // extra event
    expect(screen.getByRole('row', { name: /Quick · 4 events a month/ })).toHaveTextContent('₹799')
    expect(screen.getByText(/each extra event costs the pack's extra price/)).toBeInTheDocument()
    expect(screen.getByText(/carry over to the next month/)).toBeInTheDocument()
    expect(screen.getByText('Client gallery').parentElement).toHaveTextContent('₹250')
    expect(screen.getByText(/a full wedding of about 2,000 photos \(25 GB\) kept for 3 months is ₹1,375/)).toBeInTheDocument()
    expect(screen.getAllByText(/per GB per month/).length).toBeGreaterThan(0)
    expect(screen.getByText(/Every live event includes 5 GB/)).toBeInTheDocument()
    expect(screen.getByText(/Galleries stay open for 3 months/)).toBeInTheDocument()
  })

  it('still shows the older single plan if the server sends no packages', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      perEventPrice: 2500, monthlyPrice: 5000, monthlyEvents: 4, extraEventPrice: 999, currency: 'INR',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<PricingPage />)
    expect(await screen.findByText('₹2,500')).toBeInTheDocument()
    expect(screen.getByText(/Each extra event in a month: ₹999/)).toBeInTheDocument()
  })

  it('says so when prices cannot be loaded', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 500 })))
    render(<PricingPage />)
    expect(await screen.findByText(/Prices could not be loaded/)).toBeInTheDocument()
  })

  it('states the refund promise and how to contact', () => {
    render(<RefundsPage />)
    expect(screen.getByRole('heading', { name: 'Events you buy never expire' })).toBeInTheDocument()
    expect(screen.getByText(/5–7 working days/)).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'connect@mcreatik.com' }).length).toBeGreaterThan(0)
  })

  it('renders the terms and contact pages with links to every policy', () => {
    render(<TermsPage />)
    expect(screen.getByRole('heading', { name: 'Terms of service' })).toBeInTheDocument()
    for (const name of ['Pricing', 'Terms', 'Refunds', 'Privacy', 'Contact']) {
      expect(screen.getByRole('navigation', { name: 'Policies' })).toHaveTextContent(name)
    }
    render(<ContactPage />)
    expect(screen.getAllByRole('link', { name: '+91 99527 58545' })[0]).toHaveAttribute('href', 'https://wa.me/919952758545')
    expect(screen.getByText(/Ramapuram, Chennai, Tamil Nadu 600089/)).toBeInTheDocument()
  })
})
