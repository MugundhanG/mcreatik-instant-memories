import { BUSINESS } from './business'
import { PolicyLayout, Section } from './PolicyLayout'

const UPDATED = '4 November 2026'

/** Refund and cancellation policy. Draft for the owner to review. Every statement here must stay true. */
export default function RefundsPage() {
  return (
    <PolicyLayout title="Refunds & cancellations" updated={UPDATED}>
      <Section title="Events you buy never expire">
        <p>
          When you buy events, they are added to your studio account straight away and stay there until you use them. They
          never expire, so there is nothing to lose if an event is postponed or cancelled: use the event later.
        </p>
      </Section>

      <Section title="No refunds for bought events">
        <p>
          Because events are delivered instantly and never expire, payments for events and for the Monthly plan are not
          refunded, and events cannot be exchanged for cash.
        </p>
      </Section>

      <Section title="Charged twice, or charged for a failed payment?">
        <p>
          If you were charged more than once for the same purchase, or money left your account but the events were not
          added, we refund the extra or failed amount in full. Refunds go back to the card, bank account or UPI you paid
          with, within <b>5–7 working days</b> of us confirming the problem.
        </p>
        <p>
          Most failed payments are reversed by your bank on their own within a few days. If yours is not, contact us.
        </p>
      </Section>

      <Section title="Cancelling the Monthly plan">
        <p>
          You can stop the Monthly plan at any time by telling us. It ends at the end of the month already paid for; that
          month is not refunded. Events you bought separately stay in your account.
        </p>
      </Section>

      <Section title="How to ask">
        <p>
          Email <a className="underline underline-offset-4" href={`mailto:${BUSINESS.email}`}>{BUSINESS.email}</a> or WhatsApp{' '}
          <a className="underline underline-offset-4" href={BUSINESS.whatsappLink}>{BUSINESS.whatsapp}</a> with your studio
          name, the date and amount, and the payment ID (it starts with “pay_”) or UPI transaction ID. We reply within 2
          working days.
        </p>
      </Section>
    </PolicyLayout>
  )
}
