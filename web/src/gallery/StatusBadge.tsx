import type { EventStatus } from '../lib/types'
import type { Connection } from './useLiveGallery'

const ICON = 'size-4 shrink-0'

function CalendarIcon() {
  return (
    <svg viewBox="0 0 24 24" className={`${ICON} text-[#b37a2c]`} fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3.5" y="5" width="17" height="15.5" rx="2.5" />
      <path d="M3.5 10h17M8 3v4M16 3v4" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" className={`${ICON} text-emerald-700`} fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M8 12.5l2.8 2.8L16.5 9.5" />
    </svg>
  )
}

/** Event status in the bar under the cover: Live (pulsing dot), Upcoming (calendar) or Completed (tick). */
export function StatusBadge({ status, connection }: { status: EventStatus; connection: Connection }) {
  let icon
  let label
  let detail
  if (status === 'LIVE') {
    const reconnecting = connection === 'reconnecting'
    icon = (
      <span data-testid="live-dot" className={`size-2 shrink-0 rounded-full ${reconnecting ? 'bg-muted' : 'bg-live live-dot'}`} aria-hidden="true" />
    )
    label = 'Live'
    detail = reconnecting ? 'Reconnecting…' : 'New photos are being added'
  } else if (status === 'UPCOMING') {
    icon = <CalendarIcon />
    label = 'Upcoming'
    detail = 'Event starts soon'
  } else if (status === 'COMPLETED') {
    icon = <CheckIcon />
    label = 'Completed'
    detail = 'All photos from the event'
  } else {
    return <span />
  }
  return (
    <div className="flex min-w-0 items-center gap-2" aria-live="polite" data-testid="event-status">
      {icon}
      <span className="text-xs font-semibold tracking-[0.18em] text-ink uppercase">{label}</span>
      <span className="truncate text-xs text-muted">{detail}</span>
    </div>
  )
}
