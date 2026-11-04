/** Business details shown on every policy page (Razorpay and card networks ask for these to be public). */
export const BUSINESS = {
  name: 'McreatiK',
  product: 'McreatiK Instant Memories',
  email: 'connect@mcreatik.com',
  whatsapp: '+91 99527 58545',
  whatsappLink: 'https://wa.me/919952758545',
  /** TODO(owner): replace with the registered business address before going live with online payments. */
  address: 'Address to be added',
} as const

export const POLICY_LINKS = [
  { to: '/pricing', label: 'Pricing' },
  { to: '/terms', label: 'Terms' },
  { to: '/refunds', label: 'Refunds' },
  { to: '/privacy', label: 'Privacy' },
  { to: '/contact', label: 'Contact' },
] as const
