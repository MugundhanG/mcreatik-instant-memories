import { useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import type { EventDetail } from '../lib/types'
import { Card } from './ui'
import { useConfirm } from './useConfirm'

type Which = 'liveGallery' | 'clientGallery'

const ROWS: { key: Which; field: 'liveGalleryEnabled' | 'clientGalleryEnabled'; label: string; hint: string }[] = [
  { key: 'liveGallery', field: 'liveGalleryEnabled', label: 'Live gallery (guests)', hint: 'The QR gallery guests open. When off, they see “This gallery is currently unavailable”.' },
  { key: 'clientGallery', field: 'clientGalleryEnabled', label: 'Client gallery (couple’s login)', hint: 'The couple’s login for album selection and originals. When off, they cannot log in.' },
]

/** McreatiK only: turn an event's galleries on or off (e.g. unpaid events). Uploads keep working either way. */
export function GalleryAccessCard({ event, onChange }: { event: EventDetail; onChange: (e: EventDetail) => void }) {
  const [busy, setBusy] = useState<Which | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()

  const toggle = async (key: Which, on: boolean, label: string) => {
    if (!on) {
      const ok = await confirm({
        title: `Turn off the ${label.toLowerCase()}?`,
        body: 'People see a message asking them to contact the studio. Photos and uploads are kept; you can turn it back on at any time.',
        confirmLabel: 'Turn off',
        danger: true,
      })
      if (!ok) return
    }
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
    <Card title="Gallery access (McreatiK only)">
      {confirmDialog}
      <ul className="divide-y divide-line" data-testid="gallery-access">
        {ROWS.map((r) => {
          const on = event[r.field] !== false
          return (
            <li key={r.key} className="flex items-center justify-between gap-4 py-3 first:pt-0 last:pb-0">
              <div className="min-w-0">
                <p className="text-sm font-medium">{r.label}</p>
                <p className="text-xs text-muted">{r.hint}</p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={on}
                aria-label={r.label}
                disabled={busy !== null}
                onClick={() => toggle(r.key, !on, r.label)}
                className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50 ${on ? 'bg-emerald-600' : 'bg-stone-300'}`}
              >
                <span className={`absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow transition-transform ${on ? 'translate-x-5' : ''}`} />
              </button>
            </li>
          )
        })}
      </ul>
      {error && <p className="mt-2 text-sm text-red-700" role="alert">{error}</p>}
    </Card>
  )
}

/** What the studio sees when McreatiK has turned a gallery off (it cannot change it). */
export function GalleryAccessNotice({ event }: { event: EventDetail }) {
  // A client gallery has no live gallery by design; a couple's gallery waiting for storage has its own notice.
  const off = [
    event.liveGalleryEnabled === false && event.eventType !== 'CLIENT' && 'Live gallery',
    event.clientGalleryEnabled === false && !event.storageLocked && 'Client gallery',
  ].filter(Boolean) as string[]
  if (off.length === 0) return null
  return (
    <div role="status" className="rounded-xl border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-900" data-testid="gallery-access-notice">
      <b>{off.join(' and ')} turned off by McreatiK.</b> Guests and the couple can't open {off.length > 1 ? 'them' : 'it'} right now.
      Contact connect@mcreatik.com to turn {off.length > 1 ? 'them' : 'it'} back on.
    </div>
  )
}
