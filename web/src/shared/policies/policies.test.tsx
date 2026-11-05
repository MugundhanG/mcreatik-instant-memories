import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import ContactPage from './ContactPage'
import PricingPage from './PricingPage'
import RefundsPage from './RefundsPage'
import TermsPage from './TermsPage'

describe('Policy pages', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the current prices from the server', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      perEventPrice: 2500, monthlyPrice: 5000, monthlyEvents: 4, extraEventPrice: 999, currency: 'INR',
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<PricingPage />)
    expect(await screen.findByText('₹2,500')).toBeInTheDocument()
    expect(screen.getByText('₹5,000')).toBeInTheDocument()
    expect(screen.getByText(/4 events included/)).toBeInTheDocument()
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
