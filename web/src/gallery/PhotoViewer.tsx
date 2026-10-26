import { useEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { downloadPhoto, fetchPhoto, isIOS, sharePhoto } from '../lib/photoActions'
import type { PublicPhoto } from '../lib/types'
import { SLIDE_MS, useViewerGestures } from './useViewerGestures'

interface Props {
  photos: PublicPhoto[]
  index: number
  eventSlug: string
  eventName: string
  onIndexChange: (index: number) => void
  onClose: () => void
  onNearEnd: () => void
}

/** Share is hidden for now; flip to bring the button back. */
const SHOW_SHARE = false
const SAVE_HINT = 'Press and hold the photo, then tap “Save image”'

export function PhotoViewer({ photos, index, eventSlug, eventName, onIndexChange, onClose, onNearEnd }: Props) {
  const photo = photos[index]
  const [loadedId, setLoadedId] = useState<string | null>(null)
  const [toast, setToast] = useState<{ text: string; ms: number } | null>(null)
  // iPhone: the photo must already be fetched when Download is tapped (see downloadPhoto).
  const [prefetched, setPrefetched] = useState<{ id: string; blob: Blob } | null>(null)
  const [busy, setBusy] = useState<'download' | 'share' | null>(null)
  const stageRef = useRef<HTMLDivElement>(null)
  const imageRef = useRef<HTMLImageElement>(null)

  const hasPrev = index > 0
  const hasNext = index < photos.length - 1
  const prev = () => hasPrev && onIndexChange(index - 1)
  const next = () => hasNext && onIndexChange(index + 1)
  const { zoom, slide, handlers } = useViewerGestures({
    stageRef, imageRef, photoKey: photo?.id, canPrev: hasPrev, canNext: hasNext, onPrev: prev, onNext: next, onClose,
  })

  // Keyboard navigation + lock page scroll while open.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowLeft' && index > 0) onIndexChange(index - 1)
      if (e.key === 'ArrowRight' && index < photos.length - 1) onIndexChange(index + 1)
    }
    window.addEventListener('keydown', onKey)
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
    }
  }, [index, photos.length, onClose, onIndexChange])

  // Preload neighbours so swiping feels instant; fetch more when close to the end.
  useEffect(() => {
    for (const neighbour of [photos[index + 1], photos[index - 1]]) {
      if (neighbour) new Image().src = neighbour.webUrl
    }
    if (index >= photos.length - 5) onNearEnd()
  }, [index, photos, onNearEnd])

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), toast.ms)
    return () => clearTimeout(t)
  }, [toast])

  const currentId = photo?.id
  const currentWebUrl = photo?.webUrl
  useEffect(() => {
    if (!currentId || !currentWebUrl || !isIOS()) return
    let active = true
    fetchPhoto(currentWebUrl)
      .then((blob) => active && setPrefetched({ id: currentId, blob }))
      .catch(() => {}) // Download then falls back to the press-and-hold hint
    return () => {
      active = false
    }
  }, [currentId, currentWebUrl])

  if (!photo) return null

  const onDownload = async () => {
    setBusy('download')
    const blob = prefetched?.id === photo.id ? prefetched.blob : null
    const result = await downloadPhoto(photo.webUrl, eventSlug, photo.id, blob)
    setBusy(null)
    if (result === 'saved') setToast({ text: 'Saved to your phone', ms: 2500 })
    if (result === 'hint') setToast({ text: SAVE_HINT, ms: 6000 })
    if (result === 'unavailable') setToast({ text: 'Downloads are turned off in this demo', ms: 2500 })
  }

  const onShare = async () => {
    setBusy('share')
    const result = await sharePhoto({ url: photo.webUrl, eventSlug, eventName, photoId: photo.id })
    setBusy(null)
    if (result === 'copied') setToast({ text: 'Link copied', ms: 2500 })
    if (result === 'unsupported') setToast({ text: 'Sharing is not available on this device', ms: 2500 })
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Photo viewer"
      className="fixed inset-0 z-50 flex flex-col text-white select-none"
      // Swiping down to close fades the backdrop with the photo.
      style={{ backgroundColor: `rgba(0,0,0,${1 - Math.min(slide.y / 400, 0.6)})` }}
    >
      <div className="flex items-center justify-between px-3 pt-[max(env(safe-area-inset-top),0.75rem)] pb-2">
        <button type="button" onClick={onClose} aria-label="Close" className="flex size-11 items-center justify-center rounded-full hover:bg-white/10">
          <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
        <span className="text-sm text-white/70 tabular-nums" data-testid="viewer-counter">
          {index + 1} / {photos.length}
        </span>
        <span className="size-11" aria-hidden="true" />
      </div>

      <div ref={stageRef} data-testid="viewer-stage" className="relative min-h-0 flex-1 touch-none overflow-hidden" {...handlers}>
        {/* The neighbours sit beside the photo, so a swipe drags the next one in like a phone gallery. */}
        <div
          className="absolute inset-0"
          style={{ transform: `translate3d(${slide.x}px, ${slide.y}px, 0)`, transition: slide.animate ? `transform ${SLIDE_MS}ms ease-out` : undefined }}
        >
          {hasPrev && <Neighbour photo={photos[index - 1]} side={-1} />}
          <div
            data-testid="viewer-zoom"
            className="absolute inset-0 flex items-center justify-center"
            style={{
              transform: `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.scale})`,
              transition: zoom.animate ? 'transform 200ms ease-out' : undefined,
            }}
          >
            {/* Thumbnail shows instantly (already cached); the sharp version fades in over it. */}
            <img src={photo.thumbnailUrl} alt="" aria-hidden="true" className="absolute max-h-full max-w-full object-contain" draggable={false} />
            <img
              ref={imageRef}
              key={photo.id}
              src={photo.webUrl}
              alt={`Photo ${index + 1} of ${photos.length}`}
              onLoad={() => setLoadedId(photo.id)}
              className={`relative max-h-full max-w-full object-contain transition-opacity duration-300 ${loadedId === photo.id ? 'opacity-100' : 'opacity-0'}`}
              draggable={false}
            />
          </div>
          {hasNext && <Neighbour photo={photos[index + 1]} side={1} />}
        </div>
        {hasPrev && (
          <button type="button" onClick={prev} aria-label="Previous photo" className="absolute top-1/2 left-2 hidden size-12 -translate-y-1/2 items-center justify-center rounded-full bg-black/30 hover:bg-black/50 sm:flex">
            <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
          </button>
        )}
        {hasNext && (
          <button type="button" onClick={next} aria-label="Next photo" className="absolute top-1/2 right-2 hidden size-12 -translate-y-1/2 items-center justify-center rounded-full bg-black/30 hover:bg-black/50 sm:flex">
            <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
          </button>
        )}
      </div>

      <div className="flex items-center justify-center gap-3 px-4 pt-3 pb-[max(env(safe-area-inset-bottom),1rem)]">
        <button
          type="button"
          onClick={onDownload}
          disabled={busy !== null}
          className="inline-flex h-12 min-w-36 items-center justify-center gap-2 rounded-full bg-white px-6 text-sm font-medium text-ink disabled:opacity-60"
        >
          <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19h14" /></svg>
          {busy === 'download' ? 'Saving…' : 'Download'}
        </button>
        {SHOW_SHARE && <button
          type="button"
          onClick={onShare}
          disabled={busy !== null}
          className="inline-flex h-12 min-w-36 items-center justify-center gap-2 rounded-full border border-white/40 px-6 text-sm font-medium disabled:opacity-60"
        >
          <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 15V4m0 0L7.5 8.5M12 4l4.5 4.5M5 13v6h14v-6" /></svg>
          {busy === 'share' ? 'Sharing…' : 'Share'}
        </button>}
      </div>

      {toast && (
        <div role="status" className="pointer-events-none absolute bottom-24 left-1/2 w-max max-w-[calc(100%-2rem)] -translate-x-1/2 rounded-full bg-white px-4 py-2 text-center text-sm text-ink shadow-lg">
          {toast.text}
        </div>
      )}
    </div>
  )
}

/** The previous (-1) or next (1) photo, one screen (plus a small gap) to the side. */
function Neighbour({ photo, side }: { photo: PublicPhoto; side: -1 | 1 }) {
  const style: CSSProperties = { transform: `translateX(calc(${side * 100}% + ${side * 16}px))` }
  return (
    <div className="absolute inset-0 flex items-center justify-center" style={style} aria-hidden="true">
      <img src={photo.thumbnailUrl} alt="" className="absolute max-h-full max-w-full object-contain" draggable={false} />
      <img src={photo.webUrl} alt="" className="relative max-h-full max-w-full object-contain" draggable={false} />
    </div>
  )
}
