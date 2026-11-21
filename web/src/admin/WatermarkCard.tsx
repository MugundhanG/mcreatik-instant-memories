import { useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import type { EventDetail } from '../lib/types'
import { Card } from './ui'

/**
 * The studio's logo on photos guests download and share (on by default). Every photo a guest posts on WhatsApp or
 * Instagram then carries the studio's name. Only guests' copies are marked: the couple's originals stay clean.
 */
export function WatermarkCard({ event, onChange }: { event: EventDetail; onChange: (e: EventDetail) => void }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const on = event.watermark !== false
  const brand = event.brand

  const toggle = async () => {
    setBusy(true)
    setError(null)
    try {
      onChange(await adminApi.updateEvent(event.id, { watermark: !on }))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not change it. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Logo watermark">
      <div className="flex items-center justify-between gap-4" data-testid="watermark-card">
        <div className="min-w-0">
          <p className="text-sm font-medium">
            Studio logo on guest downloads{' '}
            <span className={`text-xs font-normal ${on ? 'text-emerald-700' : 'text-muted'}`}>{on ? 'On' : 'Off'}</span>
          </p>
          <p className="text-xs text-muted">
            {on
              ? 'Photos guests save or share carry your logo in the corner, so every photo they post advertises your studio.'
              : 'Guests save and share clean photos, with no logo.'}
            {' '}Your client’s photos are never marked.
          </p>
          {on && !brand?.logoUrl && !brand?.mcreatik && (
            <p className="mt-1 text-xs text-amber-800" data-testid="watermark-no-logo">
              No logo yet: your studio name is used. Add your logo under Account → Studio branding.
            </p>
          )}
        </div>
        <button
          type="button"
          role="switch"
          aria-checked={on}
          aria-label="Studio logo on guest downloads"
          disabled={busy}
          onClick={toggle}
          className={`relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-50 ${on ? 'bg-emerald-600' : 'bg-stone-300'}`}
        >
          <span className={`absolute top-0.5 left-0.5 size-5 rounded-full bg-white shadow transition-transform ${on ? 'translate-x-5' : ''}`} />
        </button>
      </div>
      {on && (
        <div className="relative mt-4 aspect-[3/2] w-full max-w-xs overflow-hidden rounded-xl bg-gradient-to-br from-stone-500 via-stone-700 to-stone-900" aria-hidden="true" data-testid="watermark-preview">
          <span className="absolute right-[3%] bottom-[3%] flex items-center rounded-md bg-white/80 p-1.5">
            {brand?.logoUrl
              ? <img src={brand.logoUrl} alt="" className="h-5 max-w-24 object-contain" />
              : <span className="text-[11px] font-bold text-ink">{brand?.name ?? 'Your studio'}</span>}
          </span>
        </div>
      )}
      {error && <p className="mt-2 text-sm text-red-700" role="alert">{error}</p>}
    </Card>
  )
}
