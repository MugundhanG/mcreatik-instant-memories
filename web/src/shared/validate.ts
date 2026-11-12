/** Field checks shared by the sign-in and account forms. Each returns an error message, or null when the value is fine. */

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/

export function emailError(value: string): string | null {
  const v = value.trim()
  if (!v) return 'Enter your email address'
  return EMAIL.test(v) ? null : 'Enter a valid email address, like name@studio.com'
}

export function requiredError(value: string, message: string): string | null {
  return value.trim() ? null : message
}

/** Same rule as the server (StudioBranding.normalizePhone): blank is fine; 8 to 15 digits, optionally after a +. */
export function phoneError(value: string): string | null {
  const s = value.trim()
  if (!s) return null
  let digits = s.replace(/[\s().-]/g, '')
  if (digits.startsWith('00')) digits = '+' + digits.slice(2)
  return /^\+?\d{8,15}$/.test(digits) ? null : 'Enter a phone number, e.g. 98765 43210 or +91 98765 43210'
}
