import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Brand } from '../lib/types'
import { fakeCanvas } from '../test/canvas'
import { DEFAULT_MARK } from '../lib/watermark'
import { BrandingCard } from './BrandingCard'

const brand: Brand = { name: 'Lens & Light', logoUrl: 'https://media.test/branding/logo.png', instagram: 'lenslight.in', mcreatik: false, logoScale: 100 }

let canvas: ReturnType<typeof fakeCanvas>
beforeEach(() => {
  vi.restoreAllMocks()
  canvas = fakeCanvas()
})

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
    await waitFor(() => expect(update).toHaveBeenCalledWith({ displayName: 'Lens & Light', instagram: 'lenslight.in', phone: '', logoScale: 150, mark: DEFAULT_MARK }))
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ logoScale: 150 }))
  })

  it('saves the contact number guests can WhatsApp or call, shown in Indian format', async () => {
    const user = userEvent.setup()
    const update = vi.fn(async () => ({ ...brand, phone: '+919876543210' }))
    render(<BrandingCard brand={{ ...brand, phone: '+919000011111' }} actions={{ update, upload: vi.fn(), remove: vi.fn() }} onSaved={vi.fn()} />)
    const field = screen.getByPlaceholderText('98765 43210')
    expect(field).toHaveValue('+91 90000 11111')
    await user.clear(field)
    await user.type(field, '98765 43210')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith(expect.objectContaining({ phone: '98765 43210' })))
    await waitFor(() => expect(field).toHaveValue('+91 98765 43210'))
  })

  it('has no size slider until there is a logo', () => {
    render(<BrandingCard brand={{ ...brand, logoUrl: null }} actions={{ update: vi.fn(), upload: vi.fn(), remove: vi.fn() }} onSaved={vi.fn()} />)
    expect(screen.queryByLabelText(/Logo size on the QR poster/)).not.toBeInTheDocument()
  })
})

describe('Studio branding form', () => {
  it('rejects a bad contact number before saving, and Cancel puts the saved values back', async () => {
    const user = userEvent.setup()
    const update = vi.fn()
    const onCancel = vi.fn()
    render(<BrandingCard brand={brand} actions={{ update, upload: vi.fn(), remove: vi.fn() }} onSaved={vi.fn()} onCancel={onCancel} />)
    const name = screen.getByRole('textbox', { name: /Studio name/ })
    await user.clear(name)
    await user.type(name, 'Other')
    await user.type(screen.getByPlaceholderText('98765 43210'), '123')
    await user.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByText(/Enter a phone number, e\.g\./)).toBeInTheDocument()
    expect(update).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(name).toHaveValue('Lens & Light')
    expect(onCancel).toHaveBeenCalled()
  })
})

describe('Watermark settings', () => {
  const actions = (update = vi.fn(async (input: object) => ({ ...brand, ...input }))) => ({ update, upload: vi.fn(), remove: vi.fn() })

  it('starts from the studio’s saved choices and saves the changed ones', async () => {
    const user = userEvent.setup()
    const update = vi.fn(async () => ({ ...brand }))
    render(<BrandingCard brand={{ ...brand, phone: '+919876543210' }} actions={actions(update)} onSaved={vi.fn()} />)
    const box = screen.getByTestId('mark-settings')
    expect(within(box).getByRole('radio', { name: /Corner mark/ })).toBeChecked()
    expect(within(box).getByRole('checkbox', { name: 'Logo' })).toBeChecked()
    expect(within(box).getByRole('checkbox', { name: 'Studio name' })).not.toBeChecked()
    expect(within(box).getByRole('checkbox', { name: 'Instagram handle' })).toBeChecked()

    await user.click(within(box).getByRole('radio', { name: /Signature strip/ }))
    await user.click(within(box).getByRole('checkbox', { name: 'Studio name' }))
    await user.click(within(box).getByRole('checkbox', { name: 'Phone number' }))
    await user.click(within(box).getByRole('radio', { name: 'Centre' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith(expect.objectContaining({
      mark: { style: 'STRIP', position: 'CENTER', size: 'SMALL', logo: true, name: true, instagram: true, phone: true },
    })))
  })

  it('has no Size choice for the strip, and picks Medium for the corner mark', async () => {
    const user = userEvent.setup()
    const update = vi.fn(async () => ({ ...brand }))
    render(<BrandingCard brand={brand} actions={actions(update)} onSaved={vi.fn()} />)
    await user.click(screen.getByRole('radio', { name: 'Medium' }))
    await user.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(update).toHaveBeenCalledWith(expect.objectContaining({ mark: expect.objectContaining({ size: 'MEDIUM' }) })))
    await user.click(screen.getByRole('radio', { name: /Signature strip/ }))
    expect(screen.queryByRole('radio', { name: 'Medium' })).not.toBeInTheDocument()
  })

  it('disables a detail the studio has not set, with a hint', () => {
    render(<BrandingCard brand={{ ...brand, logoUrl: null, instagram: null }} actions={actions()} onSaved={vi.fn()} />)
    const box = screen.getByTestId('mark-settings')
    expect(within(box).getByRole('checkbox', { name: 'Logo' })).toBeDisabled()
    expect(within(box).getByRole('checkbox', { name: 'Instagram handle' })).toBeDisabled()
    expect(within(box).getByRole('checkbox', { name: 'Instagram handle' })).not.toBeChecked()
    expect(within(box).getByText('Add your Instagram above')).toBeInTheDocument()
    expect(within(box).getByRole('checkbox', { name: 'Phone number' })).toBeDisabled()
    expect(within(box).getByRole('checkbox', { name: 'Studio name' })).toBeEnabled()
    expect(screen.getByText(/Turn it off for an event in its Settings/)).toBeInTheDocument()
  })

  it('previews the real mark on a sample photo and follows what is typed', async () => {
    const user = userEvent.setup()
    render(<BrandingCard brand={{ ...brand, logoUrl: null, instagram: null }} actions={actions()} onSaved={vi.fn()} />)
    expect(screen.getByTestId('watermark-preview')).toBeInTheDocument()
    expect(canvas.texts.at(-1)).toBe('Lens & Light') // nothing else available: the studio name
    await user.type(screen.getByPlaceholderText('@yourstudio'), '@lenslight')
    await waitFor(() => expect(canvas.texts.at(-1)).toBe('@lenslight'))
    await user.click(screen.getByRole('checkbox', { name: 'Studio name' }))
    await waitFor(() => expect(canvas.texts.at(-1)).toBe('Lens & Light  ·  @lenslight'))
    const el = screen.getByTestId('watermark-preview') as HTMLCanvasElement
    const corner = el.height
    await user.click(screen.getByRole('radio', { name: /Signature strip/ }))
    await waitFor(() => expect(el.height).toBeGreaterThan(corner)) // the strip adds a band under the photo
  })
})
