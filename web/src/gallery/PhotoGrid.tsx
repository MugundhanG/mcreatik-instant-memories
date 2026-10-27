import { useEffect, useMemo, useRef, useState } from 'react'
import type { PublicPhoto } from '../lib/types'

interface Props {
  photos: PublicPhoto[]
  onOpen: (photo: PublicPhoto) => void
  onNearEnd: () => void
  hasMore: boolean
}

/** Portrait-ish fallback while a photo's size is unknown. */
const DEFAULT_RATIO = 5 / 4

function columnCountFor(width: number) {
  if (width >= 1024) return 4
  if (width >= 640) return 3
  return 2
}

function useColumnCount() {
  const [count, setCount] = useState(() => (typeof window === 'undefined' ? 2 : columnCountFor(window.innerWidth)))
  useEffect(() => {
    const onResize = () => setCount(columnCountFor(window.innerWidth))
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [])
  return count
}

/**
 * Masonry columns: every photo uncropped at its own shape (portraits keep heads and feet).
 * Photos go, newest first, into the currently shortest column, so loading older photos at the bottom never moves the
 * ones already shown; new photos only reshuffle while the guest is at the top (see GalleryPage).
 * Thumbnails only; the full image loads in the viewer.
 */
export function PhotoGrid({ photos, onOpen, onNearEnd, hasMore }: Props) {
  const sentinel = useRef<HTMLDivElement>(null)
  const columnCount = useColumnCount()

  const columns = useMemo(() => {
    const cols = Array.from({ length: columnCount }, () => ({ height: 0, items: [] as { photo: PublicPhoto; index: number }[] }))
    photos.forEach((photo, index) => {
      const shortest = cols.reduce((a, b) => (b.height < a.height ? b : a))
      shortest.items.push({ photo, index })
      shortest.height += photo.width && photo.height ? photo.height / photo.width : DEFAULT_RATIO
    })
    return cols
  }, [photos, columnCount])

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
      <div data-testid="photo-grid" data-columns={columnCount} className="flex items-start gap-2 px-2.5 sm:gap-3 sm:px-4">
        {columns.map((col, c) => (
          <ul key={c} className="flex min-w-0 flex-1 flex-col gap-2 sm:gap-3">
            {col.items.map(({ photo, index }) => (
              <li
                key={photo.id}
                className="photo-in relative overflow-hidden rounded-xl bg-stone-200"
                style={{ aspectRatio: photo.width && photo.height ? `${photo.width} / ${photo.height}` : `1 / ${DEFAULT_RATIO}` }}
              >
                <button
                  type="button"
                  onClick={() => onOpen(photo)}
                  className="block size-full focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-ink"
                  aria-label={`Open photo ${index + 1}`}
                >
                  <img
                    src={photo.thumbnailUrl}
                    alt=""
                    loading={index < 8 ? 'eager' : 'lazy'}
                    decoding="async"
                    draggable={false}
                    className="size-full object-cover transition-opacity duration-300 hover:opacity-90"
                  />
                </button>
              </li>
            ))}
          </ul>
        ))}
      </div>
      <div ref={sentinel} aria-hidden="true" />
    </>
  )
}
