import { useEffect, useState } from 'react'
import { adminApi } from '../lib/api'
import type { BillingSummary, EventDetail } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'
import { rupees } from './plans'

/**
 * A Quick event that has grown past the guest limit (unique people on the gallery) is offered the upgrade to Main.
 * It never blocks anything: the gallery keeps working whether or not the studio upgrades. Below the limit, a small
 * "Quick event" note with the upgrade link.
 */
export function UpgradeBanner({ event, onUpgraded }: { event: EventDetail; onUpgraded: () => void }) {
  const [billing, setBilling] = useState<BillingSummary | null>(null)
  const [people, setPeople] = useState<number | null>(null)
  const [open, setOpen] = useState(false)
  const quick = event.eventType === 'QUICK'

  useEffect(() => {
    if (!quick) return
    adminApi.billing().then(setBilling).catch(() => {})
    adminApi.analytics(event.id).then((a) => setPeople(a.totals.people)).catch(() => {})
  }, [quick, event.id])

  if (!quick || !billing || billing.free) return null
  const limit = billing.prices.upgradeGuests
  const over = people !== null && people >= limit

  return (
    <>
      {open && (
        <BuyEventsDialog billing={billing} purchase={{ item: 'UPGRADE', eventId: event.id }}
          reason={over ? `${people} guests have opened this gallery: more than a Quick event (up to about ${limit}). Upgrade it to Main for ${rupees(billing.prices.upgrade)}. The gallery keeps working either way.` : undefined}
          onClose={() => setOpen(false)} onPaid={() => {
            setOpen(false)
            onUpgraded()
          }} />
      )}
      {over ? (
        <div role="status" data-testid="upgrade-banner" className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900">
          <span><b>{people} guests so far:</b> this has grown beyond a Quick event (about {limit} guests). Upgrade it to Main for {rupees(billing.prices.upgrade)}. Nothing stops in the meantime.</span>
          <button type="button" onClick={() => setOpen(true)} className="inline-flex h-9 items-center rounded-lg bg-ink px-3.5 font-medium text-white">Upgrade to Main</button>
        </div>
      ) : (
        <p className="text-xs text-muted" data-testid="quick-note">
          Quick event (up to about {limit} guests).{' '}
          <button type="button" onClick={() => setOpen(true)} className="font-medium text-[#8a5a1c] hover:underline">Upgrade to Main · {rupees(billing.prices.upgrade)}</button>
        </p>
      )}
    </>
  )
}
