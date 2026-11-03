import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Markdown } from './Markdown'
import { adminApi } from '../../lib/api'

describe('documentation images', () => {
  beforeEach(() => {
    vi.spyOn(adminApi, 'docImage').mockResolvedValue(new Blob(['x'], { type: 'image/webp' }))
    URL.createObjectURL = vi.fn(() => 'blob:shot')
    URL.revokeObjectURL = vi.fn()
  })
  afterEach(() => vi.restoreAllMocks())

  it('loads a screenshot with the login token and shows its caption, enlarging on click', async () => {
    render(<MemoryRouter><Markdown source={'Intro\n\n![The QR poster](images/s-print-poster.webp)'} /></MemoryRouter>)
    expect(adminApi.docImage).toHaveBeenCalledWith('s-print-poster.webp')
    const img = await screen.findByRole('img', { name: 'The QR poster' })
    expect(img).toHaveAttribute('src', 'blob:shot')
    expect(screen.getByText('The QR poster', { selector: 'figcaption' })).toBeInTheDocument()
    expect(img.closest('p')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Enlarge: The QR poster' }))
    expect(screen.getByRole('dialog', { name: 'The QR poster' })).toBeInTheDocument()
  })

  it('ignores images outside the docs image folder', () => {
    render(<MemoryRouter><Markdown source={'![x](https://example.com/a.png)'} /></MemoryRouter>)
    expect(adminApi.docImage).not.toHaveBeenCalled()
    expect(screen.queryByRole('img')).toBeNull()
  })

  it('says so when an image cannot be loaded', async () => {
    vi.mocked(adminApi.docImage).mockRejectedValueOnce(new Error('404'))
    render(<MemoryRouter><Markdown source={'![Missing](images/nope.webp)'} /></MemoryRouter>)
    await waitFor(() => expect(screen.getByText('Image could not be loaded')).toBeInTheDocument())
  })

  it('opens links to other docs pages inside the app', () => {
    render(<MemoryRouter><Markdown source={'See the [Canon guide](/admin/docs/guide-canon).'} /></MemoryRouter>)
    expect(screen.getByRole('link', { name: 'Canon guide' })).toHaveAttribute('href', '/admin/docs/guide-canon')
  })
})
