import { useEffect, useState } from 'react'
import { publicApi } from '../../lib/api'
import type { Prices, PublicPricing } from '../../lib/types'
import { BUSINESS } from './business'
import { PolicyLayout, Section } from './PolicyLayout'

const UPDATED = '6 November 2026'
const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

function PriceCard({ name, price, unit, body }: { name: string; price: number; unit: string; body: string }) {
  return (
    <div className="rounded-2xl border border-line bg-white p-5">
      <p className="font-semibold text-ink">{name}</p>
      <p className="mt-2 text-3xl font-semibold text-ink tabular-nums">{rupees(price)}</p>
      <p className="mt-1 text-sm text-muted">{unit}</p>
      <p className="mt-3 text-sm text-ink/85">{body}</p>
    </div>
  )
}

/** Main and Quick events, the monthly packs and how they work. */
function Packages({ k }: { k: Prices }) {
  const packs = [
    ['Main · 2 events a month', k.main2, 2, k.main],
    ['Main · 4 events a month', k.main4, 4, k.main],
    ['Quick · 2 events a month', k.quick2, 2, k.quick],
    ['Quick · 4 events a month', k.quick4, 4, k.quick],
  ] as const
  return (
    <div data-testid="prices">
      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        <PriceCard name="Main event" price={k.main} unit="per event"
          body="Weddings, receptions, big birthdays, big traditional functions and large corporate events." />
        <PriceCard name="Quick event" price={k.quick} unit="per event"
          body={`Engagements, small birthdays, baby showers and small corporate events, up to about ${k.upgradeGuests} guests. Bigger than planned? Upgrade it to Main for ${rupees(k.upgrade)}; the gallery never stops.`} />
      </div>
      <h2 className="mt-8 text-lg font-semibold text-ink">Monthly packs</h2>
      <div className="mt-3 overflow-hidden rounded-2xl border border-line bg-white">
        <table className="w-full text-sm">
          <thead className="bg-stone-50 text-left text-xs text-muted">
            <tr><th className="px-4 py-2 font-medium">Pack</th><th className="px-4 py-2 text-right font-medium">Per month</th><th className="px-4 py-2 text-right font-medium">Per event</th></tr>
          </thead>
          <tbody>
            {packs.map(([name, price, events]) => (
              <tr key={name} className="border-t border-line">
                <td className="px-4 py-2.5">{name}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">{rupees(price)}</td>
                <td className="px-4 py-2.5 text-right tabular-nums">{rupees(Math.round(price / events))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <ul className="mt-3 list-disc space-y-1 pl-5 text-sm text-ink/85">
        <li>A Main pack also covers Quick events; a Quick pack covers Quick events only.</li>
        <li>Unused events in a month carry over to the next month (one month only).</li>
        <li>Packs are paid month by month from your dashboard. If you don't renew, new events are simply charged one by one.</li>
        <li>Events you buy one by one never expire.</li>
      </ul>
      <h2 className="mt-8 text-lg font-semibold text-ink">Client gallery only</h2>
      <div className="mt-3 grid gap-4 sm:grid-cols-2">
        <PriceCard name="Client gallery" price={k.clientSetup} unit={`setup per gallery + ${rupees(k.storageGb)} per GB a month`}
          body={`No live event: a private gallery for the couple to pick album photos and download in full quality, with your branding. For example, a full wedding of about 2,000 photos (25 GB) kept for ${k.activeMonths} months is ${rupees(k.clientSetup + 25 * k.storageGb * k.activeMonths)}.`} />
        <PriceCard name="Storage" price={k.storageGb} unit="per GB per month"
          body={`Every live event includes ${k.freeStorageGb} GB for the couple's gallery. More is ${rupees(k.storageGb)} per GB a month (${rupees(k.storageGb * k.activeMonths)} per GB for ${k.activeMonths} months), about 80 full-quality photos per GB. Uploads at a live event are never stopped.`} />
      </div>
      <ul className="mt-3 list-disc space-y-1 pl-5 text-sm text-ink/85">
        <li>Galleries stay open for {k.activeMonths} months. Keep one longer at {rupees(k.storageGb)} per GB a month: {rupees(k.storageGb * k.activeMonths)} per GB for {k.activeMonths} more months.</li>
        <li>A gallery that isn't kept longer closes at the end of its period, and its photos are deleted 30 days later.</li>
      </ul>
    </div>
  )
}

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
        desktop uploader for cameras. Studios pay per event (Main or Quick), or take a monthly pack.
      </p>

      {failed && <p className="mt-6 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Prices could not be loaded right now. Please refresh, or contact us.</p>}
      {!p && !failed && <p className="mt-6 text-sm text-muted">Loading prices…</p>}
      {p && (p.packages ? <Packages k={p.packages} /> : (
        <div className="mt-6 grid gap-4 sm:grid-cols-2" data-testid="prices">
          <PriceCard name="Per event" price={p.perEventPrice} unit="per event" body="Pay only when you create an event. Bought events never expire." />
          <PriceCard name="Monthly" price={p.monthlyPrice} unit={`per month · ${p.monthlyEvents} events included`} body={`Each extra event in a month: ${rupees(p.extraEventPrice)}.`} />
        </div>
      ))}

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
