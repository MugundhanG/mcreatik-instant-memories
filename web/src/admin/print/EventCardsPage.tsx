import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router'
import { adminApi } from '../../lib/api'
import { formatEventDate, splitEventName } from '../../lib/format'
import type { EventDetail } from '../../lib/types'
import {
  boardSvg, CARD, cardSvg, downloadBlob, fileSlug, posterSvg, POSTER, renderJpeg, THEMES, type CardContent,
} from '../../shared/printDesign'
import { qrSvg, scanUrl } from '../../shared/qr'
import { PageLoader } from '../../shared/ShutterLoader'
import { Button, Card, ErrorText } from '../ui'
import { DesignPreview } from './printParts'
import { themeOf, usePrintAssets } from './usePrintAssets'

type Kind = 'event' | 'table' | 'poster'

/** An event's print files: the event card for the studio board and a standalone table card, both 8 × 12 in. */
export default function EventCardsPage() {
  const { eventId = '' } = useParams()
  const [event, setEvent] = useState<EventDetail | null>(null)
  const [qr, setQr] = useState<string | null>(null)
  const [making, setMaking] = useState<Kind | null>(null)
  const [percent, setPercent] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const assets = usePrintAssets(event?.brand, () => adminApi.eventLogo(eventId))

  useEffect(() => {
    adminApi.event(eventId).then(setEvent).catch(() => setError('The event could not be loaded'))
  }, [eventId])

  useEffect(() => {
    if (event) qrSvg(scanUrl(event.galleryUrl)).then(setQr)
  }, [event])

  const theme = themeOf(event?.brand)
  const content = useMemo<CardContent | null>(() => {
    if (!event || !qr) return null
    const { names, occasion } = splitEventName(event.name)
    const date = formatEventDate(event.eventDate)
    return { names, detail: (occasion ? `${occasion} · ${date}` : date).toUpperCase(), qr }
  }, [event, qr])

  const fonts = assets.fonts
  const svgs = useMemo(() => {
    if (!content || !fonts) return null
    return {
      event: cardSvg(theme, content, { table: false, fonts }),
      table: cardSvg(theme, content, { table: true, fonts }),
      board: boardSvg(theme, assets.boardBrand, { card: content, fonts }),
      poster: posterSvg(content, assets.boardBrand, { fonts }),
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- boardBrand is rebuilt each render from these values
  }, [content, fonts, theme, assets.boardBrand.name, assets.boardBrand.logo, assets.boardBrand.logoScale, assets.boardBrand.instagram, assets.boardBrand.phone])

  if (!event) return error ? <ErrorText>{error}</ErrorText> : <PageLoader label="Preparing cards" />
  const names = splitEventName(event.name).names

  const download = async (kind: Kind) => {
    if (!svgs) return
    setMaking(kind)
    setPercent(0)
    setError(null)
    try {
      const poster = kind === 'poster'
      const blob = await renderJpeg(svgs[kind], poster ? POSTER.width : CARD.width, poster ? POSTER.height : CARD.height, setPercent)
      downloadBlob(blob, poster ? `poster-${fileSlug(names)}-A4.jpg` : `${kind === 'event' ? 'event-card' : 'table-card'}-${fileSlug(names)}-8x12in.jpg`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not make the print file')
    } finally {
      setMaking(null)
    }
  }

  const printNote = 'JPG at 300 dpi, 8.25 × 12.25 in: 0.125 in extra on every side for trimming. Print at 8 × 12 in (a standard photo print) and trim to size.'

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <Link to={`/admin/events/${event.id}`} className="text-sm text-muted hover:text-ink">← {event.name}</Link>
          <h1 className="mt-1 text-2xl font-semibold">Event and table cards</h1>
          <p className="mt-1 text-sm text-muted">
            {THEMES[theme].label} design, to match your studio board. Change it in{' '}
            <Link to="/admin/account#board" className="underline underline-offset-4">Account → Studio board</Link>.
          </p>
        </div>
        <Link to={`/admin/events/${event.id}/print`} target="_blank" className="text-sm text-muted underline underline-offset-4 hover:text-ink">
          Classic A4 poster
        </Link>
      </div>
      <ErrorText>{error}</ErrorText>
      {assets.logoMissing && (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">Your logo couldn't be loaded, so the board preview shows your studio name only.</p>
      )}
      <div className="grid items-stretch gap-5 md:grid-cols-2 lg:grid-cols-4">
        <Card title="Event card" className="flex flex-col" bodyClassName="flex flex-1 flex-col">
          <p className="mb-4 text-sm text-muted">Goes in the frame of your studio board.</p>
          <div className="mx-auto mb-4 w-full max-w-48"><DesignPreview svg={svgs?.event ?? null} label="Event card" /></div>
          <Button variant="primary" className="mt-auto w-full" onClick={() => download('event')} disabled={!svgs || making !== null}>
            {making === 'event' ? `Making print file… ${percent}%` : 'Download event card'}
          </Button>
          <p className="mt-2 text-xs text-muted">{printNote}</p>
        </Card>
        <Card title="Table card" className="flex flex-col" bodyClassName="flex flex-1 flex-col">
          <p className="mb-4 text-sm text-muted">Stands on its own on guest tables, with "Scan to see the photos live".</p>
          <div className="mx-auto mb-4 w-full max-w-48"><DesignPreview svg={svgs?.table ?? null} label="Table card" /></div>
          <Button variant="primary" className="mt-auto w-full" onClick={() => download('table')} disabled={!svgs || making !== null}>
            {making === 'table' ? `Making print file… ${percent}%` : 'Download table card'}
          </Button>
          <p className="mt-2 text-xs text-muted">{printNote}</p>
        </Card>
        <Card title="A4 poster" className="flex flex-col" bodyClassName="flex flex-1 flex-col">
          <p className="mb-4 text-sm text-muted">Classic ivory and gold poster for the stage or a table.</p>
          <div className="mx-auto mb-4 w-full max-w-48"><DesignPreview svg={svgs?.poster ?? null} label="A4 poster" /></div>
          <Button variant="primary" className="mt-auto w-full" onClick={() => download('poster')} disabled={!svgs || making !== null}>
            {making === 'poster' ? `Making print file… ${percent}%` : 'Download JPG'}
          </Button>
          <p className="mt-2 text-xs text-muted">JPG at 300 dpi, A4 (2480 × 3508 px). Print at A4.</p>
        </Card>
        <Card title="On your board">
          <p className="mb-4 text-sm text-muted">How the event card looks in your studio board.</p>
          <div className="mx-auto max-w-56"><DesignPreview svg={svgs?.board ?? null} label="Studio board with this event card" /></div>
          <p className="mt-4 text-xs text-muted">
            No board yet? Download it once from <Link to="/admin/account#board" className="underline underline-offset-4">Account → Studio board</Link>.
          </p>
        </Card>
      </div>
    </div>
  )
}
