import { useCallback, useEffect, useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary, EventDetail, FaceSearchStatus } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'
import { facePackAddOn, isPack, packPrice, packShortName, PACK_EVENTS, rupees } from './plans'
import { Button, Card } from './ui'
import { useConfirm } from './useConfirm'

const POLL_MS = 8_000

type Which = 'enabled' | 'privateGallery'

/**
 * Settings: "Find my photos" for one live event, with the same on/off switches as Gallery access. Face search: guests
 * take a selfie and see the photos they are in (the studio buys it per event, then switches it freely; McreatiK can
 * switch it on for a free trial). Private gallery: guests see only their own photos, never the whole gallery.
 */
export function FaceSearchCard({ event, billing, superAdmin, onChanged }: {
  event: EventDetail
  billing: BillingSummary | null
  superAdmin: boolean
  onChanged?: () => void
}) {
  const [status, setStatus] = useState<FaceSearchStatus | null>(null)
  const [buying, setBuying] = useState<'event' | 'pack' | null>(null)
  const [busy, setBusy] = useState<Which | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()

  const load = useCallback(
    () => adminApi.faceSearch(event.id).then(setStatus).catch(() => {}),
    [event.id],
  )
  useEffect(() => {
    load()
  }, [load])
  const scanning = !!status?.enabled && status.indexed < status.total
  useEffect(() => {
    if (!scanning) return
    const timer = setInterval(load, POLL_MS)
    return () => clearInterval(timer)
  }, [scanning, load])

  if (event.eventType === 'CLIENT' || !status) return null

  const price = event.eventType === 'QUICK' ? status.quickPrice : status.mainPrice
  const percent = status.total > 0 ? Math.min(100, Math.round((status.indexed / status.total) * 100)) : 0
  // packCovered: not paid for yet, but the pack's Find my photos covers it, so the studio may switch it on for free.
  const covered = !!status.packCovered && !status.paid
  const mayToggle = superAdmin || !!status.paid || covered
  const canBuy = !status.enabled && !mayToggle && status.available && !!billing && !billing.free
  const hasPack = !!billing && !billing.free && isPack(billing.plan) && billing.packActive
  // A paid-up pack without Find my photos: offer it by the month, with the total a studio thinks in.
  const canOfferPack = canBuy && hasPack && !billing.facePackActive
  const packTotal = billing && isPack(billing.plan) ? packPrice(billing.plan, billing.prices) + facePackAddOn(billing.plan, billing.prices) : 0
  // Find my photos is on the pack but does not reach this event: say why in one line.
  const packNotCovering = canBuy && hasPack && !!billing.facePackActive
  const notCoveringWhy = !packNotCovering ? '' : event.eventType === 'MAIN' && billing.plan.startsWith('QUICK')
    ? 'Your Quick pack’s Find my photos covers Quick events only.'
    : `This month’s ${PACK_EVENTS[billing.plan] ?? ''} events with Find my photos on your pack are used.`

  const toggle = async (which: Which, on: boolean) => {
    const ok = await confirm(which === 'enabled'
      ? on
        ? { title: 'Turn on Find my photos?', body: 'Guests see a Find my photos button and can take a selfie to see the photos they are in. All the gallery’s photos are scanned for faces (a minute or two for new photos).', confirmLabel: 'Turn on' }
        : { title: 'Turn off Find my photos?', body: 'Guests can no longer search with a selfie, and the gallery’s face data is deleted (turning it on again scans all photos again). A private gallery opens to everyone again.', confirmLabel: 'Turn off', danger: true }
      : on
        ? { title: 'Make the gallery private?', body: 'Guests see only the photos they are in, after taking a selfie. Guests who don’t take a selfie see no photos. Your client gallery is not affected.', confirmLabel: 'Make private' }
        : { title: 'Open the gallery to everyone?', body: 'Every guest sees all the photos again, and can still use Find my photos.', confirmLabel: 'Open to everyone' })
    if (!ok) return
    setBusy(which)
    setError(null)
    try {
      setStatus(await adminApi.setFaceSearch(event.id, { [which]: on }))
      onChanged?.()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not change it. Please try again.')
    } finally {
      setBusy(null)
    }
  }

  const rows: { key: Which; label: string; hint: string; on: boolean; disabled: boolean; why?: string; note?: string }[] = [
    {
      key: 'enabled',
      label: 'Find my photos',
      hint: 'Guests take a selfie in the live gallery and see only the photos they are in.',
      on: status.enabled,
      disabled: !mayToggle || !status.available,
      why: !status.available ? (superAdmin ? 'Server feature flag is off.' : 'Coming soon.') : !mayToggle ? `Add it to this event first (${rupees(price)}).` : undefined,
      note: covered && !status.enabled ? 'Included in your pack’s Find my photos. Turning it on uses one of this month’s pack events.' : undefined,
    },
    {
      key: 'privateGallery',
      label: 'Private gallery',
      hint: 'Guests see only their own photos, after a selfie. Nobody sees the whole gallery.',
      on: !!status.privateGallery,
      disabled: !status.enabled,
      why: !status.enabled ? 'Needs Find my photos on.' : undefined,
    },
  ]

  return (
    <Card title="Face search">
      {confirmDialog}
      {buying && billing && (
        <BuyEventsDialog billing={billing} purchase={buying === 'pack' ? { item: 'FACE_PACK', quantity: 1 } : { item: 'FACE_SEARCH', eventId: event.id }} faceSearchPrice={price}
          onClose={() => setBuying(null)} onPaid={() => {
            setBuying(null)
            load()
            onChanged?.()
          }} />
      )}
      <ul className="divide-y divide-line" data-testid="face-search-card">
        {rows.map((r) => (
          <li key={r.key} className="flex items-center justify-between gap-4 py-3 first:pt-0 last:pb-0">
            <div className="min-w-0">
              <p className="text-sm font-medium">
                {r.label}{' '}
                <span data-testid={r.key === 'enabled' ? 'face-search-state' : 'private-gallery-state'} className={`text-xs font-normal ${r.on ? 'text-emerald-700' : 'text-muted'}`}>{r.on ? 'On' : 'Off'}</span>
              </p>
              <p className="text-xs text-muted">{r.hint}</p>
              {r.note && !r.on && <p className="mt-1 text-xs text-emerald-800" data-testid="face-search-pack-hint">{r.note}</p>}
              {r.why && !r.on && <p className="mt-1 text-xs text-amber-800" data-testid={r.key === 'enabled' ? 'face-search-unavailable' : undefined}>{r.why}</p>}
            </div>
            <button
              type="button"
              role="switch"
              aria-checked={r.on}
              aria-label={r.label}
              disabled={busy !== null || r.disabled}
              onClick={() => void toggle(r.key, !r.on)}
              className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50 ${r.on ? 'bg-emerald-600' : 'bg-stone-300'}`}
            >
              <span className={`absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow transition-transform ${r.on ? 'translate-x-5' : ''}`} />
            </button>
          </li>
        ))}
      </ul>

      {canBuy && (
        <div className="mt-3 space-y-3">
          {notCoveringWhy && <p className="text-sm text-muted" data-testid="face-pack-not-covering">{notCoveringWhy}</p>}
          <div>
            <Button variant={canOfferPack ? 'secondary' : 'primary'} onClick={() => setBuying('event')}>Add face search — {rupees(price)}</Button>
            <p className="mt-1 text-xs text-muted">{rupees(status.mainPrice)} per Main event, {rupees(status.quickPrice)} per Quick event.</p>
          </div>
          {canOfferPack && billing && (
            <div>
              <Button variant="primary" onClick={() => setBuying('pack')}>Add Find my photos to your pack — {rupees(packTotal)} a month</Button>
              <p className="mt-1 text-xs text-muted">
                {rupees(facePackAddOn(billing.plan, billing.prices))} a month on top of your {packShortName(billing.plan)}. Covers face search on your pack’s events each month.
              </p>
            </div>
          )}
        </div>
      )}

      {status.enabled && status.problem && (
        <p role="alert" data-testid="face-search-problem" className="mt-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-800">
          <b>Face scanning is paused.</b> {status.problem}
          {superAdmin && <span className="mt-1 block text-xs">Fix it in AWS, then run deploy/oracle/face-search-check.sh. Photos are rescanned by themselves within a few minutes.</span>}
        </p>
      )}

      {status.enabled && (
        <div className="mt-4 text-sm">
          <p><b className="tabular-nums">Faces indexed {formatCount(status.indexed)} of {formatCount(status.total)}</b> <span className="text-muted">photos</span></p>
          <div className="mt-2 h-2 overflow-hidden rounded-full bg-stone-100" role="progressbar" aria-label="Photos scanned for faces" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
            <div className="h-full rounded-full bg-[#b37a2c]" style={{ width: `${percent}%` }} />
          </div>
          {scanning && <p className="mt-1 text-xs text-muted">New photos are scanned within a minute or two of arriving.</p>}
          <p className="mt-3 text-xs text-muted">Put “Find your photos with a selfie” on your board or card so guests know. Face data is deleted with the gallery’s photos.</p>
        </div>
      )}
      {error && <p className="mt-2 text-sm text-red-700" role="alert">{error}</p>}
    </Card>
  )
}
