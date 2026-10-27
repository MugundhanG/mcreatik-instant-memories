import type { Connection } from './useLiveGallery'

/** "● LIVE · New photos are being added" in the bar under the cover; nothing once the event is over. */
export function LiveBadge({ live, connection }: { live: boolean; connection: Connection }) {
  if (!live) return <span />
  const reconnecting = connection === 'reconnecting'
  return (
    <div className="flex min-w-0 items-center gap-2" aria-live="polite">
      <span
        data-testid="live-dot"
        className={`size-2 shrink-0 rounded-full ${reconnecting ? 'bg-muted' : 'bg-live live-dot'}`}
        aria-hidden="true"
      />
      <span className="text-xs font-semibold tracking-[0.18em] text-ink uppercase">Live</span>
      <span className="truncate text-xs text-muted">{reconnecting ? 'Reconnecting…' : 'New photos are being added'}</span>
    </div>
  )
}
