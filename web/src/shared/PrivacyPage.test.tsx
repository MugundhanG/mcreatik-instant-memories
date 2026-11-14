import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import PrivacyPage from './PrivacyPage'

describe('Privacy page', () => {
  it('explains optional face search: consent, selfie not stored, AWS Mumbai, deletion and removal', () => {
    render(<PrivacyPage />)
    expect(screen.getByRole('heading', { name: 'Face search (optional)' })).toBeInTheDocument()
    const text = document.body.textContent ?? ''
    expect(text).toMatch(/only at events where the studio has bought it/)
    expect(text).toMatch(/your choice/)
    expect(text).toMatch(/selfie is used once/)
    expect(text).toMatch(/not saved/)
    expect(text).toMatch(/Amazon Web Services \(AWS\) in Mumbai, India/)
    expect(text).toMatch(/Face templates are deleted when a photo is removed/)
    expect(text).toMatch(/30 days after the gallery's open period ends/)
    expect(text).toMatch(/ask us to remove your face data/)
  })

  it('no longer claims guests give nothing that identifies them, without the face-search exception', () => {
    render(<PrivacyPage />)
    expect(document.body.textContent).not.toMatch(/Nothing that identifies you\./)
    expect(document.body.textContent).toMatch(/Unless you choose the optional face search below/)
  })
})
