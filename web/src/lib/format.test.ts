import { describe, expect, it } from 'vitest'
import { formatBytes, timeAgo } from './format'

describe('format', () => {
  it('formats storage sizes', () => {
    expect(formatBytes(512)).toBe('512 B')
    expect(formatBytes(1536)).toBe('1.5 KB')
    expect(formatBytes(25 * 1024 * 1024)).toBe('25 MB')
    expect(formatBytes(3.2 * 1024 ** 3)).toBe('3.2 GB')
  })

  it('formats last-seen times like the dashboard spec', () => {
    const now = Date.parse('2026-10-10T18:00:00Z')
    expect(timeAgo('2026-10-10T17:59:55Z', now)).toBe('5 seconds ago')
    expect(timeAgo('2026-10-10T17:57:00Z', now)).toBe('3 minutes ago')
    expect(timeAgo(null, now)).toBe('never')
  })
})
