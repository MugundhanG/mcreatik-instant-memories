import { useEffect, useRef, useState } from 'react'
import type { CSSProperties, FormEvent, ReactNode, RefObject } from 'react'
import { ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import { downloadPhoto, fetchPhoto, isIOS, sharePhoto } from '../lib/photoActions'
import type { PublicPhoto } from '../lib/types'
import { INSTAGRAM_HANDLE, InstagramIcon, InstagramLink } from '../shared/StudioLogo'
import { SLIDE_EASE, SLIDE_GAP, SLIDE_MS, useViewerGestures } from './useViewerGestures'
import type { Zoom } from './useViewerGestures'

interface Props {
  photos: PublicPhoto[]
  index: number
  /** All photos in the event; only some may be loaded yet (more load while swiping). */
  total: number
  eventSlug: string
  eventName: string
  onIndexChange: (index: number) => void
  onClose: () => void
  onNearEnd: () => void
  /** The client portal brings its own download (originals): hide the built-in one. */
  hideDownload?: boolean
  /** Extra buttons beside Download (the dashboard's like / hide / cover / delete). */
  actions?: (photo: PublicPhoto) => ReactNode
  /** Guests are invited to follow the studio after saving; not the photographer. */
  promoteFollow?: boolean
  /** Instagram the download toast invites guests to follow; null shows no invite. */
  instagram?: string | null
  /** Caption for Share (the studio's credit). Share is shown only when it's set: the guest gallery. */
  shareCaption?: string
  /** Guest analytics (the public gallery only): photo shown, downloaded, shared, Instagram tapped. */
  onActivity?: (type: 'PHOTO_VIEW' | 'DOWNLOAD' | 'SHARE' | 'INSTAGRAM', photoId?: string) => void
  /** Guest-only: whether this guest liked the photo, and toggling it (a heart button shows when both are set). */
  liked?: (photoId: string) => boolean
  onToggleLike?: (photoId: string) => void
  /** Guest-only: asks the studio for the full-quality photo ("Get HD photo" shows when set). Rejects with the reason. */
  onRequestHd?: (photoId: string, name: string, contact: string) => Promise<void>
  /** Shown under the counter, e.g. the file name in the dashboard. */
  caption?: (photo: PublicPhoto) => ReactNode
}

const SAVE_HINT = 'Press and hold the photo, then tap “Save image”'
const FOLLOW_SHOWN_KEY = 'mcreatik.follow-invite'

/** The follow card shows on the first download of a visit only. */
function followInviteShown() {
  try {
    return sessionStorage.getItem(FOLLOW_SHOWN_KEY) === '1'
  } catch {
    return false
  }
}

function markFollowInviteShown() {
  try {
    sessionStorage.setItem(FOLLOW_SHOWN_KEY, '1')
  } catch {
    // storage blocked: the card may show again, which is harmless
  }
}

export function PhotoViewer({
  photos, index, total, eventSlug, onIndexChange, onClose, onNearEnd, actions, promoteFollow: promote = true, caption, instagram = INSTAGRAM_HANDLE, onActivity, hideDownload = false, shareCaption, liked, onToggleLike, onRequestHd,
}: Props) {
  const photo = photos[index]
  const count = Math.max(total, photos.length)
  const [toast, setToast] = useState<{ text: string; ms: number } | null>(null)
  const [followCard, setFollowCard] = useState<string | null>(null)
  // iPhone: the photo must already be fetched when Download is tapped (see downloadPhoto).
  const [prefetched, setPrefetched] = useState<{ id: string; blob: Blob } | null>(null)
  const [busy, setBusy] = useState<'download' | 'share' | null>(null)
  const [hdOpen, setHdOpen] = useState(false)
  const [hdName, setHdName] = useState('')
  const [hdContact, setHdContact] = useState('')
  const [hdError, setHdError] = useState<string | null>(null)
  const [hdBusy, setHdBusy] = useState(false)
  const stageRef = useRef<HTMLDivElement>(null)
  const imageRef = useRef<HTMLImageElement>(null)

  const hasPrev = index > 0
  const hasNext = index < photos.length - 1
  const prev = () => hasPrev && onIndexChange(index - 1)
  const next = () => hasNext && onIndexChange(index + 1)
  const { zoom, slide, slideTo, handlers } = useViewerGestures({
    stageRef, imageRef, photoKey: photo?.id, canPrev: hasPrev, canNext: hasNext, onPrev: prev, onNext: next, onClose,
  })

  // Arrow keys slide like a swipe (latest slideTo: it changes every render).
  const slideToRef = useRef(slideTo)
  useEffect(() => {
    slideToRef.current = slideTo
  })

  // Keyboard navigation + lock page scroll while open.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowLeft') slideToRef.current(-1)
      if (e.key === 'ArrowRight') slideToRef.current(1)
    }
    window.addEventListener('keydown', onKey)
    const overflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = overflow
    }
  }, [onClose])

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
  // Each photo a guest looks at counts once per showing (swiping back to it counts again).
  const reportActivity = useRef(onActivity)
  useEffect(() => {
    reportActivity.current = onActivity
  })
  useEffect(() => {
    if (currentId) reportActivity.current?.('PHOTO_VIEW', currentId)
  }, [currentId])
  useEffect(() => {
    // iPhone (Download opens the share sheet) and Share both need the photo ready the moment it's tapped.
    if (!currentId || !currentWebUrl || !(isIOS() || shareCaption)) return
    let active = true
    fetchPhoto(currentWebUrl)
      .then((blob) => active && setPrefetched({ id: currentId, blob }))
      .catch(() => {}) // Download then falls back to the press-and-hold hint
    return () => {
      active = false
    }
  }, [currentId, currentWebUrl, shareCaption])

  if (!photo) return null

  /** `savedNote` replaces the "saved" message (Share's fallback adds the caption hint). */
  const onDownload = async (savedNote?: string) => {
    setBusy('download')
    const blob = prefetched?.id === photo.id ? prefetched.blob : null
    const result = await downloadPhoto(photo.webUrl, eventSlug, photo.id, blob)
    setBusy(null)
    if (result === 'saved' || result === 'save-sheet') onActivity?.('DOWNLOAD', photo.id)
    if (result === 'unavailable') return setToast({ text: 'Downloads are turned off in this demo', ms: 2500 })
    if (result === 'cancelled') return
    const status = result === 'hint' ? SAVE_HINT : savedNote ?? (result === 'save-sheet' ? 'Love the photos?' : 'Saved to your phone ✓')
    // Right after saving is when guests are happiest with the photos: invite them to follow the studio, with a
    // card at the top (Chrome's own download bar covers the bottom of the screen). Once per visit, so it never nags.
    if (promote && instagram && !followInviteShown()) {
      markFollowInviteShown()
      setToast(null)
      return setFollowCard(status)
    }
    setToast({ text: result === 'saved' && !savedNote ? 'Saved ✓' : status, ms: result === 'hint' ? 6000 : savedNote ? 4500 : 2500 })
  }

  const onShare = async () => {
    if (!shareCaption) return
    setBusy('share')
    const blob = prefetched?.id === photo.id ? prefetched.blob : null
    const result = await sharePhoto({ url: photo.webUrl, eventSlug, photoId: photo.id, caption: shareCaption, prefetched: blob })
    setBusy(null)
    if (result === 'shared') return onActivity?.('SHARE', photo.id)
    if (result === 'cancelled') return
    // No file sharing here (most laptops): save the photo and put the credit on the clipboard to paste when posting.
    try {
      await navigator.clipboard?.writeText(shareCaption)
    } catch {
      // clipboard blocked: the photo is still saved
    }
    await onDownload('Photo saved. The caption is copied: paste it when you post.')
  }

  const sendHd = async (e: FormEvent) => {
    e.preventDefault()
    if (!onRequestHd) return
    setHdBusy(true)
    setHdError(null)
    try {
      await onRequestHd(photo.id, hdName.trim(), hdContact.trim())
      setHdOpen(false)
      setHdContact('')
      setToast({ text: 'Request sent. The studio will send it to you.', ms: 4000 })
    } catch (err) {
      setHdError(err instanceof ApiError || err instanceof Error ? err.message : 'Could not send the request')
    } finally {
      setHdBusy(false)
    }
  }
  const isLiked = liked?.(photo.id) ?? false

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Photo viewer"
      className="anim-fade-in fixed inset-0 z-50 flex flex-col text-white select-none"
      // Swiping down to close fades the backdrop with the photo.
      style={{ backgroundColor: `rgba(0,0,0,${1 - Math.min(slide.y / 400, 0.6)})` }}
    >
      <div className="flex items-center justify-between px-3 pt-[max(env(safe-area-inset-top),0.75rem)] pb-2">
        <button type="button" onClick={onClose} aria-label="Close" className="flex size-11 items-center justify-center rounded-full hover:bg-white/10">
          <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
            <path d="M6 6l12 12M18 6L6 18" />
          </svg>
        </button>
        <div className="min-w-0 text-center">
          <span className="text-sm text-white/70 tabular-nums" data-testid="viewer-counter">
            {formatCount(index + 1)} / {formatCount(count)}
          </span>
          {caption && <div className="truncate text-xs text-white/50">{caption(photo)}</div>}
        </div>
        {liked && onToggleLike && <button
          type="button"
          onClick={() => onToggleLike(photo.id)}
          aria-pressed={isLiked}
          aria-label="Like photo"
          className="inline-flex size-11 shrink-0 items-center justify-center rounded-full hover:bg-white/10"
        >
          <svg viewBox="0 0 24 24" className={`size-6 ${isLiked ? 'text-red-500' : ''}`} fill={isLiked ? 'currentColor' : 'none'} stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 20.5s-7.5-4.6-9.2-9.3C1.7 8 3.4 5 6.6 5c2 0 3.7 1.2 5.4 3 1.7-1.8 3.4-3 5.4-3 3.2 0 4.9 3 3.8 6.2-1.7 4.7-9.2 9.3-9.2 9.3Z" /></svg>
        </button>}
        {!(liked && onToggleLike) && <span className="size-11" aria-hidden="true" />}
      </div>

      <div ref={stageRef} data-testid="viewer-stage" className="relative min-h-0 flex-1 touch-none overflow-hidden" {...handlers}>
        {/* The neighbours sit beside the photo, so a swipe drags the next one in like a phone gallery. */}
        <div
          className="absolute inset-0"
          style={{ transform: `translate3d(${slide.x}px, ${slide.y}px, 0)`, transition: slide.animate ? `transform ${SLIDE_MS}ms ${SLIDE_EASE}` : undefined }}
        >
          {/* Slides keep their key (the photo id) as they move, so the photo that slid in stays on screen as it is:
              no reload, no flicker. */}
          {[-1, 0, 1].map((offset) => {
            const p = photos[index + offset]
            if (!p) return null
            return (
              <Slide
                key={p.id}
                photo={p}
                offset={offset}
                zoom={offset === 0 ? zoom : null}
                imageRef={offset === 0 ? imageRef : null}
                alt={offset === 0 ? `Photo ${index + 1} of ${count}` : ''}
              />
            )
          })}
        </div>
        {hasPrev && (
          <button type="button" onClick={() => slideTo(-1)} aria-label="Previous photo" className="absolute top-1/2 left-2 hidden size-12 -translate-y-1/2 items-center justify-center rounded-full bg-black/30 hover:bg-black/50 sm:flex">
            <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
          </button>
        )}
        {hasNext && (
          <button type="button" onClick={() => slideTo(1)} aria-label="Next photo" className="absolute top-1/2 right-2 hidden size-12 -translate-y-1/2 items-center justify-center rounded-full bg-black/30 hover:bg-black/50 sm:flex">
            <svg viewBox="0 0 24 24" className="size-6" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M9 5l7 7-7 7" /></svg>
          </button>
        )}
      </div>

      <div className={`flex items-center justify-center gap-3 px-4 pt-3 ${onRequestHd ? 'pb-1' : 'pb-[max(env(safe-area-inset-bottom),1rem)]'}`}>
        {!hideDownload && <button
          type="button"
          onClick={() => void onDownload()}
          disabled={busy !== null}
          className="inline-flex h-12 min-w-0 flex-1 max-w-48 items-center justify-center gap-2 rounded-full bg-white px-5 text-sm font-medium text-ink disabled:opacity-60"
        >
          <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 4v11m0 0l-4.5-4.5M12 15l4.5-4.5M5 19h14" /></svg>
          {busy === 'download' ? 'Saving…' : 'Download'}
        </button>}
        {!onRequestHd && actions?.(photo)}
        {shareCaption && <button
          type="button"
          onClick={onShare}
          disabled={busy !== null}
          className="inline-flex h-12 min-w-0 flex-1 max-w-48 items-center justify-center gap-2 rounded-full border border-white/40 px-5 text-sm font-medium disabled:opacity-60"
        >
          <svg viewBox="0 0 24 24" className="size-5" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true"><path d="M12 15V4m0 0L7.5 8.5M12 4l4.5 4.5M5 13v6h14v-6" /></svg>
          {busy === 'share' ? 'Sharing…' : 'Share'}
        </button>}
      </div>
      {onRequestHd && (
        <div className="flex items-center justify-center gap-2 px-4 pb-[max(env(safe-area-inset-bottom),0.75rem)]">
          {<button
            type="button"
            onClick={() => {
              setHdError(null)
              setHdOpen(true)
            }}
            className="inline-flex h-10 items-center justify-center rounded-full bg-white/15 px-4 text-xs font-medium"
          >
            Get HD photo
          </button>}
          {actions?.(photo)}
        </div>
      )}

      {toast && (
        <div
          role="status"
          className="anim-drop-in pointer-events-none absolute top-16 left-1/2 w-max max-w-[calc(100%-2rem)] -translate-x-1/2 rounded-2xl bg-white px-4 py-2 text-center text-sm text-ink shadow-lg"
        >
          {toast.text}
        </div>
      )}

      {hdOpen && onRequestHd && (
        <div className="absolute inset-0 z-20 flex items-end justify-center bg-black/50 p-3 sm:items-center" onPointerDown={(e) => e.stopPropagation()}>
          <form
            role="dialog"
            aria-label="Get HD photo"
            onSubmit={sendHd}
            className="w-full max-w-sm space-y-3 rounded-2xl bg-white p-4 text-ink shadow-xl"
          >
            <p className="text-sm text-muted">The studio will send you the full-quality photo.</p>
            <label className="block text-sm font-medium">
              Your name (optional)
              <input value={hdName} onChange={(e) => setHdName(e.target.value)} autoComplete="name" className="mt-1 block h-10 w-full rounded-lg border border-line px-3 text-sm font-normal" />
            </label>
            <label className="block text-sm font-medium">
              Email or WhatsApp number
              <input value={hdContact} onChange={(e) => setHdContact(e.target.value)} required autoComplete="email" className="mt-1 block h-10 w-full rounded-lg border border-line px-3 text-sm font-normal" />
            </label>
            {hdError && <p role="alert" className="text-sm text-red-700">{hdError}</p>}
            <div className="flex gap-2">
              <button type="submit" disabled={hdBusy} className="flex-1 rounded-full bg-ink px-4 py-2.5 text-sm font-medium text-white disabled:opacity-60">{hdBusy ? 'Sending…' : 'Send'}</button>
              <button type="button" onClick={() => setHdOpen(false)} className="rounded-full px-4 py-2.5 text-sm text-muted hover:bg-stone-100">Cancel</button>
            </div>
          </form>
        </div>
      )}

      {followCard && instagram && (
        <div
          role="dialog"
          aria-label="Follow on Instagram"
          data-testid="follow-card"
          className="absolute top-16 left-1/2 z-10 w-[min(22rem,calc(100%-2rem))] -translate-x-1/2 rounded-2xl bg-white p-4 text-ink shadow-[0_12px_40px_rgba(0,0,0,0.35)]"
        >
          <p role="status" className="text-center text-sm text-muted">{followCard}</p>
          <div className="mt-3 flex items-center gap-3">
            <span className="flex size-11 shrink-0 items-center justify-center rounded-full bg-[linear-gradient(45deg,#f09433,#dc2743_50%,#bc1888)] text-white">
              <InstagramIcon className="size-6" />
            </span>
            <div className="min-w-0">
              <p className="text-sm font-semibold">Enjoying the photos?</p>
              <p className="truncate text-xs text-muted">Follow @{instagram} for more</p>
            </div>
          </div>
          <div className="mt-4 flex gap-2">
            <InstagramLink
              handle={instagram}
              onClick={() => {
                onActivity?.('INSTAGRAM')
                setFollowCard(null)
              }}
              className="flex-1 rounded-full bg-ink px-4 py-2.5 text-center text-sm font-medium text-white"
            >
              Follow on Instagram
            </InstagramLink>
            <button type="button" onClick={() => setFollowCard(null)} className="rounded-full px-4 py-2.5 text-sm text-muted hover:bg-stone-100">
              Not now
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

/** One photo in the swipe track: the current one (offset 0, zoomable) or a neighbour one screen to the side. */
function Slide({ photo, offset, zoom, imageRef, alt }: {
  photo: PublicPhoto
  offset: number
  zoom: Zoom | null
  imageRef: RefObject<HTMLImageElement | null> | null
  alt: string
}) {
  const [loaded, setLoaded] = useState(false)
  const position: CSSProperties = offset === 0 ? {} : { transform: `translateX(calc(${offset * 100}% + ${offset * SLIDE_GAP}px))` }
  const zoomStyle: CSSProperties = zoom
    ? { transform: `translate(${zoom.x}px, ${zoom.y}px) scale(${zoom.scale})`, transition: zoom.animate ? 'transform 200ms ease-out' : undefined }
    : {}
  // Already in the browser cache (e.g. preloaded as a neighbour): show it straight away, no fade.
  const setImage = (el: HTMLImageElement | null) => {
    if (imageRef) imageRef.current = el
    if (el?.complete && el.naturalWidth > 0 && !loaded) setLoaded(true)
  }
  return (
    <div className="absolute inset-0" style={position} aria-hidden={offset !== 0 || undefined}>
      <div data-testid={offset === 0 ? 'viewer-zoom' : undefined} className="absolute inset-0 flex items-center justify-center" style={zoomStyle}>
        {/* Thumbnail shows instantly (already cached); the sharp version fades in over it. */}
        <img src={photo.thumbnailUrl} alt="" aria-hidden="true" className="absolute max-h-full max-w-full object-contain" draggable={false} />
        <img
          ref={setImage}
          src={photo.webUrl}
          alt={alt}
          onLoad={() => setLoaded(true)}
          className={`relative max-h-full max-w-full object-contain transition-opacity duration-300 ${loaded ? 'opacity-100' : 'opacity-0'}`}
          draggable={false}
        />
      </div>
    </div>
  )
}
