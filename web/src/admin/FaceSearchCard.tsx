import { useCallback, useEffect, useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { BillingSummary, EventDetail, FaceSearchStatus } from '../lib/types'
import { BuyEventsDialog } from './BuyEventsDialog'
import { rupees } from './plans'
import { Button, Card, ErrorText } from './ui'

const POLL_MS = 8_000

/**
 * "Find my photos" for one live event: guests take a selfie and see only the photos they are in. The studio buys it
 * per event; McreatiK can also switch it on or off. Shows how many photos have been scanned so far.
 */
export function FaceSearchCard({ event, billing, superAdmin, onChanged }: {
  event: EventDetail
  billing: BillingSummary | null
  superAdmin: boolean
  onChanged?: () => void
}) {
  const [status, setStatus] = useState<FaceSearchStatus | null>(null)
  const [buying, setBuying] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

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
  const canBuy = !status.enabled && status.available && !!billing && !billing.free

  const toggle = async () => {
    setBusy(true)
    setError(null)
    try {
      setStatus(await adminApi.setFaceSearch(event.id, !status.enabled))
      onChanged?.()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Could not change face search')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Face search · Find my photos" action={
      <span data-testid="face-search-state" className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${status.enabled ? 'bg-emerald-50 text-emerald-700' : 'bg-stone-100 text-stone-600'}`}>
        {status.enabled ? 'On' : 'Off'}
      </span>
    }>
      {buying && billing && (
        <BuyEventsDialog billing={billing} purchase={{ item: 'FACE_SEARCH', eventId: event.id }} faceSearchPrice={price}
          onClose={() => setBuying(false)} onPaid={() => {
            setBuying(false)
            load()
            onChanged?.()
          }} />
      )}
      <div className="space-y-3 text-sm" data-testid="face-search-card">
        {status.enabled ? (
          <>
            <p>Guests see a <b>Find my photos</b> button in the live gallery and can take a selfie to see only the photos they are in.</p>
            <div>
              <p><b className="tabular-nums">Faces indexed {formatCount(status.indexed)} of {formatCount(status.total)}</b> <span className="text-muted">photos</span></p>
              <div className="mt-2 h-2 overflow-hidden rounded-full bg-stone-100" role="progressbar" aria-label="Photos scanned for faces" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
                <div className="h-full rounded-full bg-[#b37a2c]" style={{ width: `${percent}%` }} />
              </div>
              {scanning && <p className="mt-1 text-xs text-muted">New photos are scanned within a minute or two of arriving.</p>}
            </div>
            <p className="text-xs text-muted">Put “Find your photos with a selfie” on your board or card so guests know. Face data is deleted with the gallery’s photos.</p>
          </>
        ) : (
          <>
            <p>Let guests find themselves: they take a selfie in the live gallery and see only the photos they are in. An add-on for this event.</p>
            {!status.available && (
              <p role="status" data-testid="face-search-unavailable" className="rounded-lg bg-amber-50 px-3 py-2 text-amber-900">
                {superAdmin ? 'Server feature flag is off.' : 'Coming soon.'}
              </p>
            )}
          </>
        )}
        <div className="flex flex-wrap items-center gap-2">
          {canBuy && <Button variant="primary" onClick={() => setBuying(true)}>Add face search — {rupees(price)}</Button>}
          {superAdmin && (
            <Button role="switch" aria-checked={status.enabled} aria-label="Face search for this event" disabled={busy} onClick={toggle}>
              {status.enabled ? 'Turn off' : 'Turn on'}
            </Button>
          )}
        </div>
        <ErrorText>{error}</ErrorText>
        {!status.enabled && <p className="text-xs text-muted">{rupees(status.mainPrice)} per Main event, {rupees(status.quickPrice)} per Quick event.</p>}
      </div>
    </Card>
  )
}
