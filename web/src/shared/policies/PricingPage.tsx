import { useEffect, useState } from 'react'
import { publicApi } from '../../lib/api'
import type { PublicPricing } from '../../lib/types'
import { BUSINESS } from './business'
import { PolicyLayout, Section } from './PolicyLayout'

const UPDATED = '4 November 2026'
const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

/** Public prices, read live from the billing settings so this page always matches what studios are charged. */
export default function PricingPage() {
  const [p, setP] = useState<PublicPricing | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    publicApi.pricing().then(setP, () => setFailed(true))
  }, [])

  return (
    <PolicyLayout title="Pricing" updated={UPDATED}>
      <p className="mt-6 text-[15px] leading-relaxed text-ink/85">
        {BUSINESS.product} gives photography studios a live QR photo gallery for each event, a private client portal and a
        desktop uploader for cameras. Studios pay per event, or monthly.
      </p>

      {failed && <p className="mt-6 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Prices could not be loaded right now. Please refresh, or contact us.</p>}
      {!p && !failed && <p className="mt-6 text-sm text-muted">Loading prices…</p>}
      {p && (
        <div className="mt-6 grid gap-4 sm:grid-cols-2" data-testid="prices">
          <div className="rounded-2xl border border-line bg-white p-5">
            <p className="font-semibold text-ink">Per event</p>
            <p className="mt-2 text-3xl font-semibold text-ink tabular-nums">{rupees(p.perEventPrice)}</p>
            <p className="mt-1 text-sm text-muted">per event</p>
            <p className="mt-3 text-sm text-ink/85">Pay only when you create an event. Bought events never expire.</p>
          </div>
          <div className="rounded-2xl border border-line bg-white p-5">
            <p className="font-semibold text-ink">Monthly</p>
            <p className="mt-2 text-3xl font-semibold text-ink tabular-nums">{rupees(p.monthlyPrice)}</p>
            <p className="mt-1 text-sm text-muted">per month · {p.monthlyEvents} events included</p>
            <p className="mt-3 text-sm text-ink/85">Each extra event in a month: {rupees(p.extraEventPrice)}.</p>
          </div>
        </div>
      )}

      <Section title="What every event includes">
        <ul className="list-disc space-y-1.5 pl-5">
          <li>A live guest gallery behind one QR code, with photos appearing seconds after they are taken</li>
          <li>Wi-Fi camera uploads (up to 4 cameras per laptop) with the McreatiK uploader for Windows and Mac</li>
          <li>A private client portal with full-quality downloads and album selection</li>
          <li>Your studio's name and logo on the gallery</li>
        </ul>
      </Section>

      <Section title="Paying">
        <p>
          Prices are in Indian rupees and are the final amount you pay. Pay by card, netbanking, UPI or wallet (through
          Razorpay), or by UPI QR. There is no extra fee for paying online.
        </p>
        <p>
          See the <a className="underline underline-offset-4" href="/refunds">refund policy</a> and{' '}
          <a className="underline underline-offset-4" href="/terms">terms</a>. Questions:{' '}
          <a className="underline underline-offset-4" href={`mailto:${BUSINESS.email}`}>{BUSINESS.email}</a>.
        </p>
      </Section>
    </PolicyLayout>
  )
}
