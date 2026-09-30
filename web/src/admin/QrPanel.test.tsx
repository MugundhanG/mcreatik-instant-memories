import { render, screen, waitFor } from '@testing-library/react'
import QRCode from 'qrcode'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { qrSvg } from '../shared/qr'
import { QrPanel } from './QrPanel'

const URL_ = 'https://gallery.mcreatik.com/e/arun-priya-7k3d'

describe('Event QR code', () => {
  it('encodes exactly the public gallery URL and nothing else', () => {
    const qr = QRCode.create(URL_, { errorCorrectionLevel: 'M' })
    const encoded = qr.segments.map((s) => (s as unknown as { data: Uint8Array | string }).data)
      .map((d) => (typeof d === 'string' ? d : new TextDecoder().decode(d)))
      .join('')
    expect(encoded).toBe(URL_)
  })

  it('renders a scalable SVG QR', async () => {
    const svg = await qrSvg(URL_)
    expect(svg).toContain('<svg')
    expect(svg).toContain('viewBox')
  })

  it('shows the QR with the gallery URL and download/print actions', async () => {
    render(
      <MemoryRouter>
        <QrPanel eventId="e1" eventSlug="arun-priya-7k3d" galleryUrl={URL_} />
      </MemoryRouter>,
    )
    const qr = screen.getByRole('img', { name: `QR code for ${URL_}` })
    expect(qr).toHaveAttribute('data-qr-url', URL_)
    await waitFor(() => expect(qr.querySelector('svg')).not.toBeNull())
    expect(screen.getByText(URL_)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download PNG' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Download SVG' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Print poster' })).toHaveAttribute('href', '/admin/events/e1/print')
  })
})
