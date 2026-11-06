import { useState } from 'react'
import type { BillingSummary, EventDetail, Purchase } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'
import { formatGb, monthsLeft, photosPerGb, rupees } from './plans'
import { Button, Card } from './ui'

const GB = 1024 ** 3
const DELETE_AFTER_DAYS = 30
/** Days after a live event to add storage before the couple's gallery is paused. */
export const STORAGE_GRACE_DAYS = 14

function day(iso: string, plusDays = 0) {
  const d = new Date(`${iso}T00:00:00`)
  d.setDate(d.getDate() + plusDays)
  return d.toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' })
}

/**
 * Storage and the active period of one event: how much it uses of what it has, how long it stays open, and the
 * buttons to add storage or keep it 6 more months. McreatiK's own and unlimited studios have no limits here.
 */
export function GalleryStorageCard({ event, billing, bytes, onChanged }: {
  event: EventDetail
  billing: BillingSummary | null
  /** What the event stores now (live from the stats). */
  bytes: number
  onChanged: () => void
}) {
  const [buying, setBuying] = useState<Purchase | null>(null)
  if (!billing) return null
  const client = event.eventType === 'CLIENT'
  const quota = event.storageQuotaGb ?? 0
  const quotaBytes = quota * GB
  const over = Math.max(0, bytes - quotaBytes)
  const neededGb = Math.max(1, Math.ceil(over / GB))
  const extendGb = Math.max(1, Math.ceil(bytes / GB))
  const percent = quotaBytes > 0 ? Math.min(100, Math.round((bytes / quotaBytes) * 100)) : bytes > 0 ? 100 : 0
  const free = billing.free
  const { storageGb, activeMonths } = billing.prices
  // Storage bought now is charged for the months the gallery has left.
  const months = monthsLeft(event.activeUntil, activeMonths)

  return (
    <Card title="Gallery & storage">
      {buying && (
        <BuyEventsDialog billing={billing} purchase={buying} onClose={() => setBuying(null)} onPaid={() => {
          setBuying(null)
          onChanged()
        }} />
      )}
      <div className="space-y-4 text-sm" data-testid="storage-card">
        <div>
          <div className="flex items-baseline justify-between gap-3">
            <p><b className="tabular-nums">{formatGb(bytes)}</b> <span className="text-muted">used{!free && <> of {quota} GB</>}</span></p>
            {!free && quota > 0 && <p className="text-xs text-muted">≈ {photosPerGb(Math.max(0, (quotaBytes - bytes) / GB)).toLocaleString('en-IN')} more photos fit</p>}
          </div>
          {!free && (
            <div className="mt-2 h-2 overflow-hidden rounded-full bg-stone-100" role="progressbar" aria-label="Storage used" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
              <div className={`h-full rounded-full ${over > 0 ? 'bg-red-500' : percent > 85 ? 'bg-amber-500' : 'bg-[#b37a2c]'}`} style={{ width: `${percent}%` }} />
            </div>
          )}
        </div>

        {!free && client && quota === 0 && (
          <p className="rounded-lg bg-amber-50 px-3 py-2 text-amber-900" role="status">Add storage to start uploading photos to this client gallery.</p>
        )}
        {!free && !client && over > 0 && (
          <p data-testid="storage-over" role="status" className={`rounded-lg px-3 py-2 ${event.storageLocked ? 'bg-red-50 text-red-900' : 'bg-amber-50 text-amber-900'}`}>
            {event.storageLocked
              ? <><b>The couple's gallery is paused</b> until storage is added: this event uses {formatGb(over)} more than its {quota} GB. The guests' gallery and every photo are untouched.</>
              : <>This event uses {formatGb(over)} more than the {quota} GB included. Add {neededGb} GB ({rupees(neededGb * storageGb * months)}) by <b>{day(event.eventDate, STORAGE_GRACE_DAYS)}</b> to keep the couple's gallery open. Uploads are never stopped.</>}
          </p>
        )}

        {event.activeUntil ? (
          <p data-testid="active-until" className={event.expired ? 'font-medium text-red-700' : ''}>
            {event.expired
              ? <>Closed on {day(event.activeUntil)}: guests and the couple see "expired". Deleted for good on {day(event.activeUntil, DELETE_AFTER_DAYS)}.</>
              : <>Open until <b>{day(event.activeUntil)}</b>, then deleted on {day(event.activeUntil, DELETE_AFTER_DAYS)} unless kept longer.</>}
          </p>
        ) : <p className="text-muted">Kept with no end date.</p>}

        {!free && (
          <div className="flex flex-wrap gap-2">
            <Button variant={over > 0 || (client && quota === 0) ? 'primary' : 'secondary'}
              onClick={() => setBuying({ item: 'STORAGE', eventId: event.id, months, quantity: over > 0 ? neededGb : client && quota === 0 ? 25 : 10 })}>
              Add storage
            </Button>
            {event.activeUntil && (
              <Button variant={event.expired ? 'primary' : 'secondary'} onClick={() => setBuying({ item: 'EXTEND', eventId: event.id, quantity: extendGb })}>
                Keep {activeMonths} more months · {rupees(extendGb * storageGb * activeMonths)}
              </Button>
            )}
          </div>
        )}
        {!free && <p className="text-xs text-muted">Storage is {rupees(storageGb)} per GB a month ({rupees(storageGb * months)} per GB for the {months} month{months === 1 ? '' : 's'} this gallery has left), about {photosPerGb(1)} full-quality photos per GB. Live events include {billing.prices.freeStorageGb} GB.</p>}
      </div>
    </Card>
  )
}
