import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import markUrl from '../assets/mcreatik-mark.webp'
import { IS_DEMO } from '../demo/isDemo'
import { adminApi } from '../lib/api'
import { formatEventDate, splitEventName } from '../lib/format'
import type { EventDetail } from '../lib/types'
import { STUDIO_NAME } from '../shared/StudioLogo'
import { QrCode } from './QrPanel'

/** Smaller headline for long names so it stays on one or two lines. */
function namesSize(names: string) {
  if (names.length <= 22) return 'text-[74px]'
  if (names.length <= 34) return 'text-[60px]'
  return 'text-[48px]'
}

const CORNERS = [
  'top-5 left-5 border-t-4 border-l-4',
  'top-5 right-5 border-t-4 border-r-4',
  'bottom-5 left-5 border-b-4 border-l-4',
  'bottom-5 right-5 border-b-4 border-r-4',
]

/** A4 stage / table poster: open, press Print (prints edge to edge, one page). */
export default function PrintQrPage() {
  const { eventId = '' } = useParams()
  const [event, setEvent] = useState<EventDetail | null>(null)

  useEffect(() => {
    adminApi.event(eventId).then(setEvent)
  }, [eventId])

  if (!event) return null
  const { names, occasion } = splitEventName(event.name)
  const date = formatEventDate(event.eventDate)

  return (
    <div className="flex min-h-dvh flex-col items-center gap-6 overflow-x-auto bg-stone-200 py-8 print:block print:bg-white print:p-0">
      <div className="poster relative h-[297mm] w-[210mm] shrink-0 overflow-hidden text-center shadow-2xl print:shadow-none" data-testid="poster">
        {/* Double gold frame with corner accents */}
        <div className="absolute inset-[28px] border-2 border-[#c9a35a]" />
        <div className="absolute inset-[38px] border border-[#e3cc9a]" />
        {CORNERS.map((c) => <div key={c} className={`absolute size-[70px] border-[#b8873b] ${c}`} />)}
        <div className="absolute top-[52%] left-1/2 size-[620px] -translate-1/2 rounded-full bg-[radial-gradient(circle,rgba(217,169,78,0.16),transparent_65%)]" />

        <div className="absolute inset-[70px] flex flex-col items-center">
          <img src={markUrl} alt="" className="mt-1 h-[84px] w-auto" />
          <p className="poster-gold-text mt-3 font-display text-[21px] font-semibold tracking-[0.35em] uppercase">{STUDIO_NAME}</p>

          <p className="mt-8 inline-flex items-center gap-2 rounded-full border border-[#d9a94e] px-[18px] py-[7px] text-[11px] font-semibold tracking-[0.3em] text-[#8a5a1c]">
            <span className="size-2 rounded-full bg-live" />
            LIVE PHOTO GALLERY
          </p>

          <h1 className={`poster-gold-text mt-5 font-display leading-[1.05] font-medium text-balance ${namesSize(names)}`}>{names}</h1>
          <p className="mt-3 text-[14px] font-medium tracking-[0.35em] text-[#5b4a33] uppercase">
            {occasion ? `${occasion} · ${date}` : date}
          </p>

          <div className="poster-gold-bg mt-9 rounded-[22px] p-[5px]">
            <div className="rounded-[18px] bg-white p-5">
              <QrCode url={event.galleryUrl} className="size-[280px]" />
            </div>
          </div>

          <p className="mt-8 font-display text-[42px] leading-none text-[#2b2118]">Scan to see the photos live</p>
          <div className="mt-4 flex gap-7 text-[13px] font-medium tracking-[0.2em] text-[#5b4a33] uppercase">
            <span><b className="mr-1.5 text-[#b37a2c]">1</b>Scan</span>
            <span><b className="mr-1.5 text-[#b37a2c]">2</b>Watch live</span>
            <span><b className="mr-1.5 text-[#b37a2c]">3</b>Save</span>
          </div>
          <p className="mt-auto text-[11px] tracking-[0.25em] text-[#8a7a66] uppercase">No app · No sign-up needed</p>
        </div>
      </div>

      <div className="no-print flex items-center gap-4">
        <Link to={`/admin/events/${event.id}`} className="text-sm text-muted hover:text-ink">← Back to event</Link>
        {!IS_DEMO && (
          <button type="button" onClick={() => window.print()} className="rounded-full bg-ink px-6 py-2.5 text-sm font-medium text-white">
            Print
          </button>
        )}
      </div>
    </div>
  )
}
