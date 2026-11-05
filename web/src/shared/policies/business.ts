/** Business details shown on every policy page (Razorpay and card networks ask for these to be public). */
export const BUSINESS = {
  name: 'McreatiK',
  product: 'McreatiK Instant Memories',
  email: 'connect@mcreatik.com',
  whatsapp: '+91 99527 58545',
  whatsappLink: 'https://wa.me/919952758545',
  /** Registered business address (shown on /contact). */
  address: 'No. 5/6, Vanjinathan Street, Valluvar Salai, Ramapuram, Chennai, Tamil Nadu 600089',
} as const

export const POLICY_LINKS = [
  { to: '/pricing', label: 'Pricing' },
  { to: '/terms', label: 'Terms' },
  { to: '/refunds', label: 'Refunds' },
  { to: '/privacy', label: 'Privacy' },
  { to: '/contact', label: 'Contact' },
] as const
