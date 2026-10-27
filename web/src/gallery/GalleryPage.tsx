import { useCallback, useEffect, useRef } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import logoLightUrl from '../assets/mcreatik-studios-logo-light.webp'
import { formatCount, formatEventDate, splitEventName } from '../lib/format'
import type { PublicPhoto } from '../lib/types'
import { StatusBadge } from './StatusBadge'
import { PhotoGrid } from './PhotoGrid'
import { STUDIO_NAME } from '../shared/StudioLogo'
import { PhotoViewer } from './PhotoViewer'
import { useLiveGallery } from './useLiveGallery'

/** Guests closer than this to the top see new photos inserted immediately. */
const AUTO_INSERT_SCROLL_PX = 240

export default function GalleryPage() {
  const { slug = '' } = useParams()
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const openedHere = useRef(false)
  const selectedId = params.get('photo')

  // While a photo is open, new arrivals wait behind the "new photos" button so the viewer's index never shifts.
  const viewerOpen = useRef(false)
  useEffect(() => {
    viewerOpen.current = selectedId !== null
  }, [selectedId])
  const shouldShowImmediately = useCallback(() => window.scrollY < AUTO_INSERT_SCROLL_PX && !viewerOpen.current, [])
  const gallery = useLiveGallery(slug, shouldShowImmediately)
  const { event, photos, incoming, status, connection, nextCursor, loadMore, showIncoming } = gallery

  useEffect(() => {
    if (event) document.title = `${event.name} · Live Gallery`
  }, [event])

  const selectedIndex = selectedId ? photos.findIndex((p) => p.id === selectedId) : -1

  const open = (photo: PublicPhoto) => {
    openedHere.current = true
    setParams({ photo: photo.id }) // pushes history: the phone's back button closes the viewer
  }
  const close = useCallback(() => {
    if (openedHere.current) {
      openedHere.current = false
      navigate(-1)
    } else {
      setParams({}, { replace: true })
    }
  }, [navigate, setParams])
  const changeIndex = useCallback(
    (i: number) => setParams({ photo: photos[i].id }, { replace: true }),
    [photos, setParams],
  )

  const revealIncoming = () => {
    showIncoming()
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  if (status === 'loading') {
    return (
      <main className="flex min-h-dvh items-center justify-center">
        <span className="text-sm tracking-[0.2em] text-muted uppercase">Loading gallery…</span>
      </main>
    )
  }

  if (status === 'not-found' || status === 'error' || !event) {
    return (
      <main className="flex min-h-dvh flex-col items-center justify-center px-8 text-center">
        <p className="font-display text-3xl">{status === 'not-found' ? 'This gallery isn’t available' : 'Something went wrong'}</p>
        <p className="mt-3 max-w-sm text-sm text-muted">
          {status === 'not-found'
            ? 'Please check the QR code or link, or ask your photographer.'
            : 'Please check your connection and try again.'}
        </p>
        {status === 'error' && (
          <button type="button" onClick={() => window.location.reload()} className="mt-6 rounded-full border border-ink px-5 py-2 text-sm">
            Try again
          </button>
        )}
      </main>
    )
  }

  const { names, occasion } = splitEventName(event.name)
  const date = formatEventDate(event.eventDate)
  // The photographer's chosen cover; until one is set, the newest photo.
  const cover = event.coverUrl ?? photos[0]?.webUrl ?? null

  return (
    <div className="min-h-dvh">
      <header className="relative h-[68svh] max-h-[640px] min-h-[420px] overflow-hidden bg-[#3a2a20] text-white">
        {cover ? (
          <img src={cover} alt="" data-testid="gallery-cover" className="absolute inset-0 size-full object-cover" />
        ) : (
          <div className="absolute inset-0 bg-[radial-gradient(circle_at_60%_35%,rgba(255,240,210,0.45),transparent_45%),linear-gradient(160deg,#c9a27a,#6d4a35_60%,#2e211a)]" />
        )}
        <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,0,0,0.45)_0%,transparent_28%,transparent_45%,rgba(0,0,0,0.8)_100%)]" />
        {/* Watermark: transparent logo straight on the photo */}
        <img src={logoLightUrl} alt={STUDIO_NAME} className="absolute top-4 left-4 h-14 w-auto opacity-90 drop-shadow-[0_2px_6px_rgba(0,0,0,0.45)] sm:top-6 sm:left-6 sm:h-16" />
        <div className="absolute inset-x-0 bottom-0 px-6 pb-12 sm:px-10 sm:pb-14">
          <p className="text-[11px] font-semibold tracking-[0.35em] text-[#f3dfb4] uppercase">{STUDIO_NAME}</p>
          <h1 className="mt-2 font-display text-[44px] leading-[1.02] font-medium text-balance sm:text-6xl">{names}</h1>
          <p className="mt-2 text-sm text-white/85">{occasion ? `${occasion} · ${date}` : date}</p>
        </div>
      </header>

      <div className="relative z-10 mx-3 -mt-6 flex items-center justify-between gap-3 rounded-2xl bg-white px-4 py-3 shadow-[0_10px_30px_rgba(0,0,0,0.12)] sm:mx-auto sm:max-w-xl">
        <StatusBadge status={event.status} connection={connection} />
        <span className="shrink-0 text-xs whitespace-nowrap text-muted">
          {event.photoCount > 0 ? `${formatCount(event.photoCount)} photos` : 'Photos coming soon'}
        </span>
      </div>

      {incoming.length > 0 && (
        <div className="sticky top-3 z-30 flex justify-center">
          <button
            type="button"
            onClick={revealIncoming}
            className="rounded-full bg-ink px-5 py-2.5 text-sm font-medium text-white shadow-lg"
          >
            {incoming.length === 1 ? '1 new photo' : `${incoming.length} new photos`} ↑
          </button>
        </div>
      )}

      <main className="pt-4">
        {photos.length === 0 ? (
          <div className="px-8 py-24 text-center">
            <p className="font-display text-2xl">The first photos will appear here soon</p>
            <p className="mt-2 text-sm text-muted">Keep this page open. New photos show up automatically.</p>
          </div>
        ) : (
          <PhotoGrid photos={photos} onOpen={open} onNearEnd={loadMore} hasMore={nextCursor !== null} />
        )}
        {gallery.loadingMore && <p className="py-6 text-center text-xs text-muted">Loading more…</p>}
      </main>

      <footer className="px-6 py-12 text-center text-xs text-muted">
        Live gallery by <span className="font-medium text-ink">McreatiK Studios</span>
      </footer>

      {selectedIndex >= 0 && (
        <PhotoViewer
          photos={photos}
          index={selectedIndex}
          eventSlug={event.slug}
          eventName={event.name}
          onIndexChange={changeIndex}
          onClose={close}
          onNearEnd={loadMore}
        />
      )}
    </div>
  )
}
