import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MCREATIK_BRAND, resolveBrand } from '../shared/brand'
import { ClientFooter } from './ClientFooter'

const studio = resolveBrand({ name: 'Lens & Light', logoUrl: null, instagram: 'lensandlight', mcreatik: false, phone: '+919876543210' })

describe('Client gallery footer', () => {
  it("shows the studio's contact details and the copyright line, with no policy links", () => {
    render(<ClientFooter brand={studio} />)
    const footer = screen.getByTestId('client-footer')
    expect(footer).toHaveTextContent('Lens & Light')
    expect(screen.getByRole('link', { name: /WhatsApp \+91 98765 43210/ })).toHaveAttribute('href', expect.stringContaining('https://wa.me/919876543210'))
    expect(screen.getByRole('link', { name: 'Call' })).toHaveAttribute('href', 'tel:+919876543210')
    expect(screen.getByRole('link', { name: /@lensandlight/ })).toHaveAttribute('href', 'https://www.instagram.com/lensandlight/')
    expect(footer).toHaveTextContent(`© ${new Date().getFullYear()} McreatiK · connect@mcreatik.com`)
    for (const name of [/Pricing/, /Terms/, /Refunds/, /Privacy/, /^Contact$/]) expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
  })

  it('leaves out contact details the studio has not set', () => {
    render(<ClientFooter brand={{ ...studio, phone: null, instagram: null }} />)
    expect(screen.queryByRole('link', { name: /WhatsApp/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /^@/ })).not.toBeInTheDocument()
    expect(screen.getByTestId('client-footer')).toHaveTextContent('connect@mcreatik.com')
  })
})

describe('McreatiK brand', () => {
  it('uses what the super admin saved and the built-in design for what is empty', () => {
    const empty = resolveBrand({ name: 'McreatiK Studios', logoUrl: null, instagram: null, mcreatik: true, phone: null })
    expect(empty).toEqual(MCREATIK_BRAND)
    const own = resolveBrand({ name: 'McreatiK Weddings', logoUrl: 'https://m/logo.png', instagram: 'mck', mcreatik: true, phone: '+919000000000', logoScale: 120 })
    expect(own).toMatchObject({ name: 'McreatiK Weddings', instagram: 'mck', phone: '+919000000000', heroLogo: 'https://m/logo.png', posterLogoScale: 120, poweredByMcreatik: false })
  })
})
