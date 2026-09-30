import { useEffect, useRef, useState } from 'react'
import { downloadPhoto, sharePhoto } from '../lib/photoActions'
import type { PublicPhoto } from '../lib/types'

interface Props {
  photos: PublicPhoto[]
  index: number
  eventSlug: string
  eventName: string
  onIndexChange: (index: number) => void
  onClose: () => void
  onNearEnd: () => void
}

const SWIPE_PX = 50

export function PhotoViewer({ photos, index, eventSlug, eventName, onIndexChange, onClose, onNearEnd }: Props) {
  const photo = photos[index]
  const [loadedId, setLoadedId] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [busy, setBusy] = useState<'download' | 'share' | null>(null)
  const start = useRef<{ x: number; y: number } | null>(null)

  const hasPrev = index > 0
  const hasNext = index < photos.length - 1
  const prev = () => hasPrev && onIndexChange(index - 1)
  const next = () => hasNext && onIndexChange(index + 1)

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
    const t = setTimeout(() => setToast(null), 2500)
    return () => clearTimeout(t)
  }, [toast])

  if (!photo) return null

  const onDownload = async () => {
    setBusy('download')
    const result = await downloadPhoto(photo.webUrl, eventSlug, photo.id)
    setBusy(null)
    if (result === 'unavailable') setToast('Downloads are turned off in this demo')
  }

  const onShare = async () => {
    setBusy('share')
    const result = await sharePhoto({ url: photo.webUrl, eventSlug, eventName, photoId: photo.id })
    setBusy(null)
    if (result === 'copied') setToast('Link copied')
    if (result === 'unsupported') setToast('Sharing is not available on this device')
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Photo viewer"
      className="fixed inset-0 z-50 flex flex-col bg-black text-white select-none"
      onPointerDown={(e) => (start.current = { x: e.clientX, y: e.clientY })}
      onPointerUp={(e) => {
        if (!start.current) return
        const dx = e.clientX - start.current.x
        const dy = e.clientY - start.current.y
        start.current = null
        if (Math.abs(dx) > SWIPE_PX && Math.abs(dx) > Math.abs(dy)) {
          if (dx < 0) next()
          else prev()
        } else if (dy > 120 && Math.abs(dy) > Math.abs(dx)) {
          onClose()
        }
      }}
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

      <div className="relative flex min-h-0 flex-1 items-center justify-center">
        {/* Thumbnail shows instantly (already cached); the sharp version fades in over it. */}
        <img src={photo.thumbnailUrl} alt="" aria-hidden="true" className="absolute max-h-full max-w-full object-contain" />
        <img
          key={photo.id}
          src={photo.webUrl}
          alt={`Photo ${index + 1} of ${photos.length}`}
          onLoad={() => setLoadedId(photo.id)}
          className={`relative max-h-full max-w-full object-contain transition-opacity duration-300 ${loadedId === photo.id ? 'opacity-100' : 'opacity-0'}`}
          draggable={false}
        />
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
        <button
          type="button"
          onClick={onShare}
          disabled={busy !== null}
          className="inline-flex h-12 min-w-36 items-center justify-center gap-2 rounded-full border border-white/40 px-6 text-sm font-medium disabled:opacity-60"
        >
          <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 15V4m0 0L7.5 8.5M12 4l4.5 4.5M5 13v6h14v-6" /></svg>
          {busy === 'share' ? 'Sharing…' : 'Share'}
        </button>
      </div>

      {toast && (
        <div role="status" className="pointer-events-none absolute bottom-24 left-1/2 -translate-x-1/2 rounded-full bg-white px-4 py-2 text-sm text-ink shadow-lg">
          {toast}
        </div>
      )}
    </div>
  )
}
