import type { Outgoing } from './sendLinks'

export interface Credentials {
  name: string
  email: string
  password: string
  /** WhatsApp number typed when creating the login (not stored); without it WhatsApp asks whom to send to. */
  phone?: string
}

/** The message the super admin sends the studio (WhatsApp/email). */
export function loginMessage(c: Credentials) {
  return `Your McreatiK Instant Memories login for ${c.name}\n\nDashboard: ${window.location.origin}/admin\nEmail: ${c.email}\nTemporary password: ${c.password}\n\nYou'll be asked to choose your own password when you first log in.`
}

export const outgoing = (c: Credentials): Outgoing => ({
  email: c.email,
  phone: c.phone,
  subject: `Your McreatiK Instant Memories login for ${c.name}`,
  message: loginMessage(c),
})

