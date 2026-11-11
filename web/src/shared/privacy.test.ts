import { describe, expect, it } from 'vitest'
import { removalRequestLink } from './privacy'

describe('removalRequestLink', () => {
  it('names the photo by its camera file name, with the id as a reference', () => {
    const body = decodeURIComponent(removalRequestLink('Arun & Priya', { id: 'abc-123', fileName: 'IMG_1234.JPG' }))
    expect(body).toContain('Photo: IMG_1234.JPG')
    expect(body).toContain('Reference: abc-123')
  })

  it('falls back to the id when the file name is unknown', () => {
    const body = decodeURIComponent(removalRequestLink('Arun & Priya', { id: 'abc-123' }))
    expect(body).toContain('Photo: abc-123')
    expect(body).not.toContain('Reference:')
  })
})
