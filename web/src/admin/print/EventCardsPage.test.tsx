import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import EventCardsPage from './EventCardsPage'

vi.mock('../../lib/api', () => ({
  adminApi: { event: async () => ({ id: 'e1', name: 'Arun & Priya Wedding', eventDate: '2026-10-10', galleryUrl: 'https://x.test/e/a', brand: undefined }), eventLogo: async () => new Blob() },
}))
vi.mock('../../shared/qr', () => ({ qrSvg: async () => '<svg xmlns="http://www.w3.org/2000/svg"/>', scanUrl: (u: string) => u }))
vi.mock('./usePrintAssets', () => ({
  themeOf: () => 'cream',
  usePrintAssets: () => ({ ready: true, error: false, fonts: { css: '' }, boardBrand: { name: 'Studio', logo: null, logoScale: 100, instagram: null }, logoMissing: false }),
}))

describe('Event cards page', () => {
  it('offers the A4 poster as a JPG download', async () => {
    render(
      <MemoryRouter initialEntries={['/admin/events/e1/cards']}>
        <Routes><Route path="/admin/events/:eventId/cards" element={<EventCardsPage />} /></Routes>
      </MemoryRouter>,
    )
    expect(await screen.findByText('A4 poster')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Download JPG' })).toBeEnabled()
  })
})
