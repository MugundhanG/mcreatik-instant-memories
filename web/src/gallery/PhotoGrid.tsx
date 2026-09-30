import { useEffect, useRef } from 'react'
import type { PublicPhoto } from '../lib/types'

interface Props {
  photos: PublicPhoto[]
  onOpen: (photo: PublicPhoto) => void
  onNearEnd: () => void
  hasMore: boolean
}

/**
 * Square tiles: a stable grid that never reflows when new photos are prepended.
 * Thumbnails only; the full image loads in the viewer.
 */
export function PhotoGrid({ photos, onOpen, onNearEnd, hasMore }: Props) {
  const sentinel = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const node = sentinel.current
    if (!node || !hasMore) return
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && onNearEnd(), {
      rootMargin: '1200px 0px',
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [onNearEnd, hasMore])

  return (
    <>
      <ul data-testid="photo-grid" className="grid grid-cols-2 gap-0.5 sm:grid-cols-3 sm:gap-1 lg:grid-cols-4 xl:grid-cols-5">
        {photos.map((photo, i) => (
          <li key={photo.id} className="photo-in relative aspect-square overflow-hidden bg-stone-200">
            <button
              type="button"
              onClick={() => onOpen(photo)}
              className="block size-full focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ink"
              aria-label={`Open photo ${i + 1}`}
            >
              <img
                src={photo.thumbnailUrl}
                alt=""
                loading={i < 8 ? 'eager' : 'lazy'}
                decoding="async"
                draggable={false}
                className="size-full object-cover transition-opacity duration-300 hover:opacity-90"
              />
            </button>
          </li>
        ))}
      </ul>
      <div ref={sentinel} aria-hidden="true" />
    </>
  )
}
