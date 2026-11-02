import { useEffect, useRef, useState } from 'react'
import type { EventStatus } from '../lib/types'
import { EventStatusBadge } from './ui'

const STATUSES: { status: EventStatus; hint: string }[] = [
  { status: 'DRAFT', hint: 'Hidden from guests while you set up' },
  { status: 'UPCOMING', hint: 'Guests can open it; turns Live with the first camera photo' },
  { status: 'LIVE', hint: 'New photos appear for guests as they arrive' },
  { status: 'COMPLETED', hint: 'The event is over; the gallery stays open' },
  { status: 'ARCHIVED', hint: 'Gallery hidden from guests; cameras stop uploading' },
]

/**
 * The event's status as a pill that opens a short menu. Each choice says what it does, so a click is never a
 * surprise; the page asks again before archiving (see onChange's caller).
 */
export function EventStatusMenu({ status, onChange }: { status: EventStatus; onChange: (status: EventStatus) => void }) {
  const [open, setOpen] = useState(false)
  const [at, setAt] = useState({ top: 0, left: 0, width: 288 })
  const box = useRef<HTMLDivElement>(null)

  // Placed in the viewport (not inside the header), so the menu never runs off a phone screen.
  const toggle = () => {
    const r = box.current?.getBoundingClientRect()
    if (r) {
      const width = Math.min(288, window.innerWidth - 32)
      setAt({ top: r.bottom + 8, left: Math.max(16, Math.min(r.left, window.innerWidth - 16 - width)), width })
    }
    setOpen((o) => !o)
  }

  useEffect(() => {
    if (!open) return
    const close = (e: Event) => {
      if (e instanceof KeyboardEvent ? e.key === 'Escape' : !box.current?.contains(e.target as Node)) setOpen(false)
    }
    const shut = () => setOpen(false)
    document.addEventListener('pointerdown', close)
    document.addEventListener('keydown', close)
    window.addEventListener('scroll', shut, { passive: true })
    window.addEventListener('resize', shut)
    return () => {
      document.removeEventListener('pointerdown', close)
      document.removeEventListener('keydown', close)
      window.removeEventListener('scroll', shut)
      window.removeEventListener('resize', shut)
    }
  }, [open])

  return (
    <div ref={box} className="relative">
      <button type="button" aria-haspopup="menu" aria-expanded={open} aria-label={`Status: ${status.toLowerCase()}. Change status`}
        onClick={toggle} className="flex items-center gap-1 rounded-full hover:opacity-80">
        <EventStatusBadge status={status} />
        <svg viewBox="0 0 16 16" className="size-3.5 text-muted" aria-hidden="true"><path d="M4 6l4 4 4-4" fill="none" stroke="currentColor" strokeWidth="1.6" /></svg>
      </button>
      {open && (
        <div role="menu" aria-label="Event status" style={{ top: at.top, left: at.left, width: at.width }} className="fixed z-40 rounded-xl border border-line bg-white p-1.5 shadow-lg">
          {STATUSES.map(({ status: s, hint }) => (
            <button key={s} type="button" role="menuitemradio" aria-checked={s === status}
              onClick={() => {
                setOpen(false)
                if (s !== status) onChange(s)
              }}
              className={`flex w-full items-start gap-3 rounded-lg px-2.5 py-2 text-left hover:bg-stone-50 ${s === status ? 'bg-stone-50' : ''}`}>
              <span className="mt-0.5 w-[6.5rem] shrink-0"><EventStatusBadge status={s} /></span>
              <span className="text-xs leading-5 text-muted">{hint}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
