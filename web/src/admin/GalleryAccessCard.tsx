import { useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import type { EventDetail } from '../lib/types'
import { Card } from './ui'
import { useConfirm } from './useConfirm'

type Which = 'liveGallery' | 'clientGallery'

const ROWS: { key: Which; field: 'liveGalleryEnabled' | 'clientGalleryEnabled'; blocked: 'liveGalleryBlocked' | 'clientGalleryBlocked'; label: string; hint: string }[] = [
  { key: 'liveGallery', field: 'liveGalleryEnabled', blocked: 'liveGalleryBlocked', label: 'Guest gallery', hint: 'The QR gallery guests open. When off, guests see “This gallery is currently unavailable”.' },
  { key: 'clientGallery', field: 'clientGalleryEnabled', blocked: 'clientGalleryBlocked', label: 'Client gallery', hint: 'The couple’s login for album selection and originals. When off, they cannot log in.' },
]

/**
 * Turn an event's guest and client galleries on or off. Uploads keep working either way: photos are saved to the
 * cloud, they just stay out of sight while a gallery is off. A studio cannot turn a gallery on while its paid period
 * has ended, storage is unpaid, or McreatiK turned it off: the reason is shown instead.
 */
export function GalleryAccessCard({ event, onChange, superAdmin = false }: { event: EventDetail; onChange: (e: EventDetail) => void; superAdmin?: boolean }) {
  const [busy, setBusy] = useState<Which | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()
  const rows = ROWS.filter((r) => r.key !== 'liveGallery' || event.eventType !== 'CLIENT')

  const toggle = async (key: Which, on: boolean, label: string) => {
    const ok = await confirm(on
      ? {
          title: `Turn on the ${label.toLowerCase()}?`,
          body: key === 'liveGallery'
            ? 'Guests can open the QR gallery again and see the photos that are not hidden.'
            : 'Your client can log in again and see the client gallery.',
          confirmLabel: 'Turn on',
        }
      : {
          title: `Turn off the ${label.toLowerCase()}?`,
          body: 'People see a message asking them to contact the studio. Photos and uploads are kept; you can turn it back on at any time.',
          confirmLabel: 'Turn off',
          danger: true,
        })
    if (!ok) return
    setBusy(key)
    setError(null)
    try {
      onChange(await adminApi.setGalleryAccess(event.id, { [key]: on }))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not change it. Please try again.')
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card title="Gallery access">
      {confirmDialog}
      <ul className="divide-y divide-line" data-testid="gallery-access">
        {rows.map((r) => {
          const on = event[r.field] !== false
          const why = !on && !superAdmin ? event[r.blocked] : null
          return (
            <li key={r.key} className="flex items-center justify-between gap-4 py-3 first:pt-0 last:pb-0">
              <div className="min-w-0">
                <p className="text-sm font-medium">{r.label} <span className={`text-xs font-normal ${on ? 'text-emerald-700' : 'text-muted'}`}>{on ? 'On' : 'Off'}</span></p>
                <p className="text-xs text-muted">{r.hint}</p>
                {why && <p className="mt-1 text-xs text-amber-800" data-testid={`access-blocked-${r.key}`}>Can't turn on: {why}</p>}
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={on}
                aria-label={r.label}
                disabled={busy !== null || !!why}
                onClick={() => toggle(r.key, !on, r.label)}
                className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50 ${on ? 'bg-emerald-600' : 'bg-stone-300'}`}
              >
                <span className={`absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow transition-transform ${on ? 'translate-x-5' : ''}`} />
              </button>
            </li>
          )
        })}
      </ul>
      <p className="mt-3 text-xs text-muted">Uploads still go to the cloud while a gallery is off; only guests and your client can't see it.</p>
      {error && <p className="mt-2 text-sm text-red-700" role="alert">{error}</p>}
    </Card>
  )
}
