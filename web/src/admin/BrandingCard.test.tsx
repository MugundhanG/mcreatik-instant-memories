import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { Brand } from '../lib/types'
import { BrandingCard } from './BrandingCard'

const brand: Brand = { name: 'Lens & Light', logoUrl: 'https://media.test/branding/logo.png', instagram: 'lenslight.in', mcreatik: false, logoScale: 100 }

describe('Studio branding', () => {
  it('sizes the poster logo with a live preview and saves it', async () => {
    const user = userEvent.setup()
    const update = vi.fn(async (input: { logoScale?: number }) => ({ ...brand, logoScale: input.logoScale }))
    const onSaved = vi.fn()
    render(<BrandingCard brand={brand} actions={{ update, upload: vi.fn(), remove: vi.fn() }} onSaved={onSaved} />)

    const preview = screen.getByTestId('poster-preview').querySelector('img')!
    expect(preview.style.height).toBe('42px')
    fireEvent.change(screen.getByLabelText(/Logo size on the QR poster/), { target: { value: '150' } })
    expect(screen.getByText('150%')).toBeInTheDocument()
    expect(preview.style.height).toBe('63px')

    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith({ displayName: 'Lens & Light', instagram: 'lenslight.in', logoScale: 150 }))
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ logoScale: 150 }))
  })

  it('has no size slider until there is a logo', () => {
    render(<BrandingCard brand={{ ...brand, logoUrl: null }} actions={{ update: vi.fn(), upload: vi.fn(), remove: vi.fn() }} onSaved={vi.fn()} />)
    expect(screen.queryByLabelText(/Logo size on the QR poster/)).not.toBeInTheDocument()
  })
})
