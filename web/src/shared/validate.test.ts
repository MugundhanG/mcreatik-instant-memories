import { describe, expect, it } from 'vitest'
import { emailError, phoneError } from './validate'

describe('validate', () => {
  it('checks emails', () => {
    expect(emailError('')).toMatch(/Enter your email/)
    expect(emailError('abc')).toMatch(/valid email/)
    expect(emailError('a@b')).toMatch(/valid email/)
    expect(emailError(' owner@studio.com ')).toBeNull()
  })
  it('checks phones like the server', () => {
    expect(phoneError('')).toBeNull()
    expect(phoneError('98765 43210')).toBeNull()
    expect(phoneError('+91 98765 43210')).toBeNull()
    expect(phoneError('0091 98765 43210')).toBeNull()
    expect(phoneError('12345')).toMatch(/Enter a phone number/)
    expect(phoneError('abcdefghij')).toMatch(/Enter a phone number/)
  })
})
