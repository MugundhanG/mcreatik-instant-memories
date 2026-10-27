import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { MouseEvent, ReactNode } from 'react'
import { PhotoViewer } from '../gallery/PhotoViewer'
import { adminApi } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import { downloadPhoto } from '../lib/photoActions'
import type { AdminPhoto, AdminPhotoQuery, AdminPhotoSort, PhotoFacets, PublicPhoto, UploaderStats } from '../lib/types'
import { DownloadIcon, EyeIcon, HeartIcon, MoreIcon, StarIcon, TrashIcon } from './icons'
import { Button, Card } from './ui'
import { useConfirm } from './useConfirm'

const statusLabel: Record<AdminPhoto['status'], string> = {
  QUEUED: 'Queued',
  UPLOADING: 'Uploading',
  UPLOADED: 'Processing',
  PROCESSING: 'Processing',
  READY: 'Ready',
  FAILED: 'Failed',
}

const PAGE = 60

const selectClass = 'h-9 rounded-lg border border-line bg-white px-2.5 text-sm outline-none focus:border-ink focus:ring-1 focus:ring-ink'

type Show = 'all' | 'liked' | 'hidden'

interface Filters {
  uploaderId: string
  show: Show
  day: string
  sort: AdminPhotoSort
}

const NO_FILTERS: Filters = { uploaderId: '', show: 'all', day: '', sort: 'NEWEST' }

function toQuery(f: Filters): AdminPhotoQuery {
  return {
    sort: f.sort,
    uploaderId: f.uploaderId || undefined,
    liked: f.show === 'liked' ? true : undefined,
    hidden: f.show === 'hidden' ? true : undefined,
    day: f.day || undefined,
  }
}

/** Whether a photo still belongs in the list after it was liked or hidden. */
function matches(p: AdminPhoto, f: Filters) {
  return (f.show !== 'liked' || p.liked) && (f.show !== 'hidden' || p.hidden)
}

function toViewer(p: AdminPhoto): PublicPhoto {
  return { id: p.id, thumbnailUrl: p.thumbnailUrl ?? '', webUrl: p.webUrl ?? '', width: p.width, height: p.height, readyAt: p.readyAt ?? p.createdAt, cursor: p.id }
}

interface Props {
  eventId: string
  eventSlug: string
  eventName: string
  coverPhotoId: string | null
  /** Changes when photos finish uploading: new ones are merged in without moving the list. */
  refreshKey: number
  uploaders: UploaderStats[]
  onCoverChange: (id: string) => void
  /** The event changed on the server (e.g. hiding the cover photo cleared the cover). */
  onEventChanged: () => void
  /** Photo counts changed (delete / hide): refresh the dashboard numbers. */
  onPhotosChanged: () => void
}

export function PhotosPanel({ eventId, eventSlug, eventName, coverPhotoId, refreshKey, uploaders, onCoverChange, onEventChanged, onPhotosChanged }: Props) {
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [photos, setPhotos] = useState<AdminPhoto[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [facets, setFacets] = useState<PhotoFacets | null>(null)
  const [viewerId, setViewerId] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()
  const loadingMore = useRef(false)

  const cameraName = useMemo(() => new Map(uploaders.map((u) => [u.id, u.name])), [uploaders])

  const loadFacets = useCallback(() => adminApi.photoFacets(eventId).then(setFacets).catch(() => {}), [eventId])

  // A new filter or sort starts the list from the top.
  useEffect(() => {
    let active = true
    adminApi
      .photos(eventId, { ...toQuery(filters), offset: 0, limit: PAGE })
      .then((page) => {
        if (!active) return
        setPhotos(page.photos)
        setHasMore(page.nextOffset !== null)
      })
      .catch(() => {})
      .finally(() => active && setLoading(false))
    return () => {
      active = false
    }
  }, [eventId, filters])

  // New uploads: refresh the first page in place. Existing photos keep their position (no jump back to the top),
  // photos that just finished processing get their thumbnails, brand-new ones appear at the top.
  const firstRefresh = useRef(true)
  useEffect(() => {
    if (firstRefresh.current) {
      firstRefresh.current = false
      return
    }
    loadFacets()
    adminApi
      .photos(eventId, { ...toQuery(filters), offset: 0, limit: PAGE })
      .then((page) => {
        setPhotos((list) => {
          const fresh = new Map(page.photos.map((p) => [p.id, p]))
          const updated = list.map((p) => fresh.get(p.id) ?? p)
          const known = new Set(list.map((p) => p.id))
          const added = filters.sort === 'NEWEST' ? page.photos.filter((p) => !known.has(p.id)) : []
          return [...added, ...updated]
        })
      })
      .catch(() => {})
    // filters intentionally omitted: a filter change reloads through the effect above
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [refreshKey])

  useEffect(() => {
    loadFacets()
  }, [loadFacets])

  useEffect(() => {
    if (!note) return
    const t = setTimeout(() => setNote(null), 2500)
    return () => clearTimeout(t)
  }, [note])

  /** Next page starts after the photos already shown, so deleting some never skips or repeats any. */
  const more = useCallback(async () => {
    if (loadingMore.current || !hasMore) return
    loadingMore.current = true
    try {
      const page = await adminApi.photos(eventId, { ...toQuery(filters), offset: photos.length, limit: PAGE })
      setPhotos((list) => {
        const known = new Set(list.map((p) => p.id))
        return [...list, ...page.photos.filter((p) => !known.has(p.id))]
      })
      setHasMore(page.nextOffset !== null)
    } finally {
      loadingMore.current = false
    }
  }, [eventId, filters, hasMore, photos.length])

  const apply = (updated: AdminPhoto) =>
    setPhotos((list) => list.map((p) => (p.id === updated.id ? updated : p)).filter((p) => p.id !== updated.id || matches(p, filters)))

  const toggleLike = async (p: AdminPhoto) => {
    apply({ ...p, liked: !p.liked }) // instant feedback
    try {
      apply(await adminApi.updatePhoto(p.id, { liked: !p.liked }))
      loadFacets()
    } catch {
      apply(p)
    }
  }

  const toggleHidden = async (p: AdminPhoto) => {
    const updated = await adminApi.updatePhoto(p.id, { hidden: !p.hidden })
    apply(updated)
    setNote(updated.hidden ? 'Hidden from the guest gallery' : 'Back in the guest gallery')
    loadFacets()
    onPhotosChanged()
    if (updated.hidden && p.id === coverPhotoId) onEventChanged()
  }

  const remove = async (p: AdminPhoto) => {
    const ok = await confirm({
      title: 'Delete this photo?',
      body: `${p.originalFileName} is removed from the gallery, including from guests' open screens. This cannot be undone.`,
      confirmLabel: 'Delete photo',
      danger: true,
    })
    if (!ok) return
    await adminApi.deletePhoto(p.id)
    setPhotos((list) => list.filter((x) => x.id !== p.id))
    loadFacets()
    onPhotosChanged()
    if (p.id === coverPhotoId) onEventChanged()
  }

  const download = async (p: AdminPhoto) => {
    if (!p.webUrl) return
    const result = await downloadPhoto(p.webUrl, eventSlug, p.id)
    setNote(result === 'saved' || result === 'save-sheet' ? 'Downloaded' : 'Could not download this photo')
  }

  const original = async (p: AdminPhoto) => {
    const { url } = await adminApi.originalUrl(p.id)
    window.open(url, '_blank', 'noopener')
  }

  const makeCover = (p: AdminPhoto) => {
    onCoverChange(p.id)
    setNote('Set as the gallery cover')
  }

  // ---- viewer: ready photos of the current list ----
  const viewable = useMemo(() => photos.filter((p) => p.status === 'READY' && p.webUrl), [photos])
  const viewerPhotos = useMemo(() => viewable.map(toViewer), [viewable])
  const viewerIndex = viewerId ? viewable.findIndex((p) => p.id === viewerId) : -1
  const byId = useMemo(() => new Map(photos.map((p) => [p.id, p])), [photos])
  const filtered = filters.uploaderId !== '' || filters.show !== 'all' || filters.day !== ''

  const removeFromViewer = async (p: AdminPhoto) => {
    const i = viewable.findIndex((x) => x.id === p.id)
    await remove(p)
    // Stay in the viewer on the next photo (or the previous one at the end).
    setViewerId((current) => {
      if (current !== p.id) return current
      const rest = viewable.filter((x) => x.id !== p.id)
      return rest.length ? rest[Math.min(i, rest.length - 1)].id : null
    })
  }

  const days = facets?.days ?? []
  const set = (patch: Partial<Filters>) => {
    setLoading(true)
    setFilters((f) => ({ ...f, ...patch }))
  }

  return (
    <Card title="Photos" action={<span className="text-xs text-muted">{note ?? `${formatCount(photos.length)}${hasMore ? '+' : ''} shown`}</span>}>
      <div className="mb-4 flex flex-wrap gap-2" data-testid="photo-filters">
        <select aria-label="Camera" className={selectClass} value={filters.uploaderId} onChange={(e) => set({ uploaderId: e.target.value })}>
          <option value="">All cameras</option>
          {uploaders.map((u) => (
            <option key={u.id} value={u.id}>{u.name} ({formatCount(u.photosUploaded)})</option>
          ))}
        </select>
        <select aria-label="Show" className={selectClass} value={filters.show} onChange={(e) => set({ show: e.target.value as Show })}>
          <option value="all">All photos</option>
          <option value="liked">♥ Liked ({formatCount(facets?.liked ?? 0)})</option>
          <option value="hidden">Hidden from guests ({formatCount(facets?.hidden ?? 0)})</option>
        </select>
        <select aria-label="Day" className={selectClass} value={filters.day} onChange={(e) => set({ day: e.target.value })}>
          <option value="">All days</option>
          {days.map((d) => (
            <option key={d.day} value={d.day}>{formatEventDate(d.day)} ({formatCount(d.photos)})</option>
          ))}
        </select>
        <select aria-label="Sort" className={selectClass} value={filters.sort} onChange={(e) => set({ sort: e.target.value as AdminPhotoSort })}>
          <option value="NEWEST">Newest first</option>
          <option value="OLDEST">Oldest first</option>
          <option value="CAMERA">By camera</option>
        </select>
        {filtered && (
          <Button variant="ghost" onClick={() => set({ ...NO_FILTERS, sort: filters.sort })}>Clear filters</Button>
        )}
      </div>

      {!loading && photos.length === 0 && <p className="text-sm text-muted">{filtered ? 'No photos match these filters.' : 'No photos yet.'}</p>}
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5" data-testid="admin-photos">
        {photos.map((p) => {
          const ready = p.status === 'READY' && !!p.webUrl
          return (
            <li key={p.id} className="min-w-0">
              <div className="relative aspect-square overflow-hidden rounded-lg bg-stone-100">
                {p.thumbnailUrl ? (
                  <button type="button" className="block size-full" onClick={() => ready && setViewerId(p.id)} aria-label={`View ${p.originalFileName}`}>
                    <img src={p.thumbnailUrl} alt="" loading="lazy" className={`size-full object-cover ${p.hidden ? 'opacity-45' : ''}`} />
                  </button>
                ) : (
                  <div className="flex size-full items-center justify-center text-xs text-muted">{statusLabel[p.status]}</div>
                )}
                <div className="pointer-events-none absolute top-1.5 left-1.5 flex flex-col items-start gap-1">
                  {p.id === coverPhotoId && <span className="rounded bg-ink px-1.5 py-0.5 text-[10px] text-white">Cover</span>}
                  {p.hidden && <span className="inline-flex items-center gap-1 rounded bg-white/90 px-1.5 py-0.5 text-[10px]"><EyeIcon off className="size-3" />Hidden</span>}
                </div>
                {ready && (
                  <button
                    type="button"
                    onClick={() => toggleLike(p)}
                    aria-label={p.liked ? 'Unlike' : 'Like'}
                    aria-pressed={p.liked}
                    className={`absolute top-1.5 right-1.5 flex size-8 items-center justify-center rounded-full bg-white/90 shadow-sm ${p.liked ? 'text-rose-600' : 'text-stone-500 hover:text-rose-600'}`}
                  >
                    <HeartIcon filled={p.liked} />
                  </button>
                )}
                {p.status !== 'READY' && (
                  <span className={`absolute right-1.5 bottom-1.5 rounded px-1.5 py-0.5 text-[10px] ${p.status === 'FAILED' ? 'bg-red-600 text-white' : 'bg-white/90'}`}>
                    {statusLabel[p.status]}
                  </span>
                )}
              </div>
              <p className="mt-1 truncate text-xs text-muted" title={p.failureReason ?? p.originalFileName}>
                {p.status === 'FAILED' ? p.failureReason : p.originalFileName}
                {p.uploaderId && cameraName.get(p.uploaderId) ? ` · ${cameraName.get(p.uploaderId)}` : ''}
              </p>
              <div className="mt-1 grid grid-cols-4 text-stone-700">
                {ready ? (
                  <>
                    <IconButton label="Download" text="Download" onClick={() => download(p)}><DownloadIcon /></IconButton>
                    <IconButton label={p.hidden ? 'Show in gallery' : 'Hide from gallery'} text={p.hidden ? 'Show' : 'Hide'} onClick={() => toggleHidden(p)}>
                      <EyeIcon off={!p.hidden} />
                    </IconButton>
                  </>
                ) : (
                  <span className="col-span-2" />
                )}
                <IconButton label="Delete" text="Delete" onClick={() => remove(p)} className="text-red-700 hover:bg-red-50"><TrashIcon /></IconButton>
                {ready && (
                  <details className="relative">
                    <summary className="flex h-11 cursor-pointer list-none flex-col items-center justify-center gap-0.5 rounded-md text-[10px] hover:bg-stone-100" aria-label="More">
                      <MoreIcon />
                      <span aria-hidden="true">More</span>
                    </summary>
                    <div className="absolute right-0 z-20 mt-1 w-48 rounded-lg border border-line bg-white p-1 text-sm shadow-lg">
                      <MenuItem
                        disabled={p.hidden || p.id === coverPhotoId}
                        onClick={(e) => { closeMenu(e); makeCover(p) }}
                      >
                        <StarIcon />{p.id === coverPhotoId ? 'Gallery cover' : 'Make cover'}
                      </MenuItem>
                      {p.originalAvailable && (
                        <MenuItem onClick={(e) => { closeMenu(e); original(p) }}><DownloadIcon />Download original</MenuItem>
                      )}
                    </div>
                  </details>
                )}
              </div>
            </li>
          )
        })}
      </ul>
      {confirmDialog}
      {hasMore && (
        <div className="mt-4 text-center">
          <Button onClick={more}>Load more</Button>
        </div>
      )}

      {viewerIndex >= 0 && (
        <PhotoViewer
          photos={viewerPhotos}
          index={viewerIndex}
          total={viewerPhotos.length}
          eventSlug={eventSlug}
          eventName={eventName}
          promoteFollow={false}
          onIndexChange={(i) => setViewerId(viewable[i].id)}
          onClose={() => setViewerId(null)}
          onNearEnd={more}
          caption={(vp) => {
            const p = byId.get(vp.id)
            if (!p) return null
            const camera = p.uploaderId ? cameraName.get(p.uploaderId) : null
            return `${p.originalFileName}${camera ? ` · ${camera}` : ''}${p.hidden ? ' · Hidden from guests' : ''}${p.id === coverPhotoId ? ' · Cover' : ''}`
          }}
          actions={(vp) => {
            const p = byId.get(vp.id)
            if (!p) return null
            return (
              <>
                <ViewerButton label={p.liked ? 'Unlike' : 'Like'} pressed={p.liked} onClick={() => toggleLike(p)}>
                  <HeartIcon filled={p.liked} className={`size-5 ${p.liked ? 'text-rose-500' : ''}`} />
                </ViewerButton>
                <ViewerButton label={p.hidden ? 'Show in gallery' : 'Hide from gallery'} pressed={p.hidden} onClick={() => toggleHidden(p)}>
                  <EyeIcon off={!p.hidden} className="size-5" />
                </ViewerButton>
                <ViewerButton label="Make cover" disabled={p.hidden || p.id === coverPhotoId} onClick={() => makeCover(p)}>
                  <StarIcon className="size-5" />
                </ViewerButton>
                <ViewerButton label="Delete" onClick={() => removeFromViewer(p)}>
                  <TrashIcon className="size-5" />
                </ViewerButton>
              </>
            )
          }}
        />
      )}
    </Card>
  )
}

function closeMenu(e: MouseEvent) {
  e.currentTarget.closest('details')?.removeAttribute('open')
}

function IconButton({ label, text, onClick, children, className = 'hover:bg-stone-100' }: { label: string; text: string; onClick: () => void; children: ReactNode; className?: string }) {
  return (
    <button type="button" title={label} aria-label={label} onClick={onClick} className={`flex h-11 flex-col items-center justify-center gap-0.5 rounded-md text-[10px] ${className}`}>
      {children}
      <span aria-hidden="true">{text}</span>
    </button>
  )
}

function MenuItem({ onClick, disabled, children }: { onClick: (e: MouseEvent) => void; disabled?: boolean; children: ReactNode }) {
  return (
    <button type="button" disabled={disabled} onClick={onClick} className="flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left hover:bg-stone-50 disabled:opacity-40 disabled:hover:bg-transparent">
      {children}
    </button>
  )
}

function ViewerButton({ label, onClick, children, pressed, disabled }: { label: string; onClick: () => void; children: ReactNode; pressed?: boolean; disabled?: boolean }) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className="flex size-11 shrink-0 items-center justify-center rounded-full border border-white/30 text-white hover:bg-white/10 disabled:opacity-35"
    >
      {children}
    </button>
  )
}
