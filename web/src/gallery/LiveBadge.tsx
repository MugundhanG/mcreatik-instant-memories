import type { Connection } from './useLiveGallery'

export function LiveBadge({ live, connection }: { live: boolean; connection: Connection }) {
  if (!live) return null
  const reconnecting = connection === 'reconnecting'
  return (
    <div className="flex flex-col items-center gap-1.5" aria-live="polite">
      <span className="inline-flex items-center gap-2 rounded-full border border-line bg-white px-3 py-1 text-[11px] font-semibold tracking-[0.18em] text-ink uppercase">
        <span
          data-testid="live-dot"
          className={`size-2 rounded-full ${reconnecting ? 'bg-muted' : 'bg-live live-dot'}`}
          aria-hidden="true"
        />
        Live
      </span>
      <span className="text-xs text-muted">{reconnecting ? 'Reconnecting…' : 'New photos are being added'}</span>
    </div>
  )
}
