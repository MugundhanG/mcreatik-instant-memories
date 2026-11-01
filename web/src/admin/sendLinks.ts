/** Ready-to-send login messages: WhatsApp chat, Gmail compose or the computer's mail app. */
export interface Outgoing {
  email: string
  /** WhatsApp number; without it WhatsApp asks whom to send to. */
  phone?: string
  subject: string
  message: string
}

/** Digits for wa.me: a 10-digit Indian mobile gets the 91 country code. */
export function whatsappNumber(phone: string | undefined) {
  const digits = (phone ?? '').replace(/\D/g, '').replace(/^0+/, '')
  return digits.length === 10 ? `91${digits}` : digits
}

/** Opens WhatsApp (app or web) with the message ready to send. */
export const whatsappLink = (o: Outgoing) => `https://wa.me/${whatsappNumber(o.phone)}?text=${encodeURIComponent(o.message)}`

/** Gmail's compose window in a new tab: works without a mail app installed on the computer. */
export const gmailLink = (o: Outgoing) =>
  `https://mail.google.com/mail/?${new URLSearchParams({ view: 'cm', fs: '1', to: o.email, su: o.subject, body: o.message })}`

/** The computer's own mail app (Outlook, Apple Mail…), when one is set up. */
export const mailAppLink = (o: Outgoing) =>
  `mailto:${o.email}?subject=${encodeURIComponent(o.subject)}&body=${encodeURIComponent(o.message)}`

/** Readable temporary password: no look-alike characters (0/O, 1/l). */
export function temporaryPassword() {
  const chars = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(12))
  return Array.from(bytes, (b) => chars[b % chars.length]).join('')
}
