import { BUSINESS } from './business'
import { PolicyLayout, Section } from './PolicyLayout'

const UPDATED = '6 November 2026'

/** Terms of service for studios. Draft for the owner to review (ideally with a professional). */
export default function TermsPage() {
  return (
    <PolicyLayout title="Terms of service" updated={UPDATED}>
      <Section title="The service">
        <p>
          {BUSINESS.product} is run by {BUSINESS.name}. It lets photography studios share event photos with their clients
          and guests: a live guest gallery behind a QR code, a private client portal, and an uploader app for cameras. By
          creating or using a studio account you agree to these terms.
        </p>
      </Section>

      <Section title="Your account">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>Studio accounts are created by McreatiK. Keep your password private; you are responsible for what is done with your login.</li>
          <li>Tell us straight away if you think someone else has used your account.</li>
          <li>We may pause an account that is used to break the law or these terms, after telling you why where we can.</li>
        </ul>
      </Section>

      <Section title="Your photos">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>Your photos stay yours. You give us permission only to store, process and show them as needed to run the service for you.</li>
          <li>You confirm you have the right to upload and share each photo, and the consent your clients and guests need.</li>
          <li>Do not upload unlawful content. We act on removal requests as described in our <a className="underline underline-offset-4" href="/privacy">privacy notice</a>.</li>
          <li>Each gallery is open for 3 months, about 90 days (from the event date, or from creation for a client gallery) and can be kept open longer by paying for more months. A gallery that closes is deleted permanently 30 days later; we email you before it closes. Deleting an event yourself permanently removes its photos at once.</li>
          <li>Keep your own copy of your photos. The gallery is for sharing, not your only backup.</li>
        </ul>
      </Section>

      <Section title="Payments">
        <p>
          Prices are shown on the <a className="underline underline-offset-4" href="/pricing">pricing page</a> and in your
          dashboard, in Indian rupees, and are the final amount you pay. Online payments are processed by Razorpay; we never
          see or store your card or bank details. Bought events are added to your account at once and never expire. Storage is bought per event and lasts as long as that event's gallery. See the{' '}
          <a className="underline underline-offset-4" href="/refunds">refund policy</a>.
        </p>
      </Section>

      <Section title="Availability">
        <p>
          We work hard to keep galleries online, especially on event days, but we cannot promise the service will never be
          interrupted (for example by internet or provider outages). The uploader keeps photos on your computer and sends
          them when the connection returns.
        </p>
      </Section>

      <Section title="Liability">
        <p>
          To the extent the law allows, our total liability for any claim about the service is limited to what you paid us in
          the 3 months before the claim. We are not liable for indirect losses, such as lost business.
        </p>
      </Section>

      <Section title="Changes and law">
        <p>
          We may update these terms; the date above shows the latest version, and we will tell studios about important
          changes. These terms are governed by the laws of India.
        </p>
      </Section>

      <Section title="Contact">
        <p>
          <a className="underline underline-offset-4" href={`mailto:${BUSINESS.email}`}>{BUSINESS.email}</a> · WhatsApp{' '}
          <a className="underline underline-offset-4" href={BUSINESS.whatsappLink}>{BUSINESS.whatsapp}</a>
        </p>
      </Section>
    </PolicyLayout>
  )
}
