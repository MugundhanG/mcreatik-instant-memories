import { useCallback, useEffect, useRef } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { formatCount, formatEventDate } from '../lib/format'
import type { PublicPhoto } from '../lib/types'
import { LiveBadge } from './LiveBadge'
import { PhotoGrid } from './PhotoGrid'
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

  return (
    <div className="min-h-dvh">
      <header className="px-6 pt-10 pb-8 text-center sm:pt-14 sm:pb-10">
        <p className="text-[11px] font-medium tracking-[0.3em] text-muted uppercase">McreatiK Studios</p>
        <h1 className="mt-4 font-display text-4xl leading-tight font-medium text-balance sm:text-5xl">{event.name}</h1>
        <p className="mt-2 text-sm text-muted">
          {formatEventDate(event.eventDate)}
          {event.photoCount > 0 && <> · {formatCount(event.photoCount)} photos</>}
        </p>
        <div className="mt-5 flex justify-center">
          <LiveBadge live={event.live} connection={connection} />
        </div>
      </header>

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

      <main>
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
