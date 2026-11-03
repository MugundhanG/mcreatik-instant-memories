import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { UploadersPanel } from './UploadersPanel'

describe('UploadersPanel', () => {
  it('links to the uploader installers for Windows and both kinds of Mac', () => {
    render(<UploadersPanel eventId="e1" uploaders={[]} onChange={() => {}} />)
    const links = ['Windows', 'Mac (M1–M4)', 'Mac (Intel)'].map((name) => screen.getByRole('link', { name }))
    expect(links.map((l) => l.getAttribute('href'))).toEqual([
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Windows.msi',
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Mac-AppleSilicon.dmg',
      'https://github.com/MugundhanG/mcreatik-instant-memories/releases/latest/download/McreatiK-Instant-Memories-Uploader-Mac-Intel.dmg',
    ])
  })
})
