import { BillingSettingsCard, PaymentsToCheck } from './BillingAdmin'

/** Super admin: payments waiting to be checked, and the prices and payment details guests and studios see. */
export default function PricingPage() {
  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-semibold">Pricing &amp; payments</h1>
        <p className="text-sm text-muted">Check payments, and set prices and payment details.</p>
      </div>
      <PaymentsToCheck onChange={() => {}} />
      <BillingSettingsCard />
    </div>
  )
}
