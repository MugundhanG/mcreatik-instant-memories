import { BUSINESS } from './business'
import { PolicyLayout, Section } from './PolicyLayout'

const UPDATED = '4 November 2026'

export default function ContactPage() {
  return (
    <PolicyLayout title="Contact us" updated={UPDATED}>
      <Section title="Get in touch">
        <ul className="space-y-1.5">
          <li>Email: <a className="underline underline-offset-4" href={`mailto:${BUSINESS.email}`}>{BUSINESS.email}</a></li>
          <li>WhatsApp: <a className="underline underline-offset-4" href={BUSINESS.whatsappLink}>{BUSINESS.whatsapp}</a></li>
          <li>We reply within 2 working days, and faster on event days.</li>
        </ul>
      </Section>
      <Section title="Business">
        <p>{BUSINESS.name} ({BUSINESS.product})</p>
        <p>{BUSINESS.address}</p>
      </Section>
      <Section title="Payments and refunds">
        <p>
          For a payment problem, include your studio name, the amount and the payment ID (it starts with “pay_”). See the{' '}
          <a className="underline underline-offset-4" href="/refunds">refund policy</a>.
        </p>
      </Section>
    </PolicyLayout>
  )
}
