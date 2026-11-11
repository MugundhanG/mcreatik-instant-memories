import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { PhotoViewer } from '../gallery/PhotoViewer'
import { adminApi, type BulkPhotoAction } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import { downloadPhoto } from '../lib/photoActions'
import type { AdminPhoto, AdminPhotoQuery, AdminPhotoSort, Category, Gallery, PhotoFacets, PublicPhoto, UploaderStats } from '../lib/types'
import { DownloadIcon, EyeIcon, HeartIcon, MoreIcon, StarIcon, TrashIcon } from './icons'
import { Button, Card } from './ui'
import { useZipDownload } from '../shared/zipDownload'
import { ZipProgressCard } from '../shared/ZipProgressCard'
import { useConfirm } from './useConfirm'
import { usePresence } from '../shared/usePresence'

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

type Show = 'all' | 'liked' | 'hidden' | 'selected'

interface Filters {
  uploaderId: string
  show: Show
  day: string
  /** '' all, a category id, or 'none' for photos not sorted yet */
  category: string
  sort: AdminPhotoSort
  /** Part of the file name */
  q: string
}

const NO_FILTERS: Filters = { uploaderId: '', show: 'all', day: '', category: '', sort: 'NEWEST', q: '' }

function toQuery(f: Filters): AdminPhotoQuery {
  return {
    sort: f.sort,
    uploaderId: f.uploaderId || undefined,
    liked: f.show === 'liked' ? true : undefined,
    hidden: f.show === 'hidden' ? true : undefined,
    day: f.day || undefined,
    category: f.category || undefined,
    selected: f.show === 'selected' ? true : undefined,
    q: f.q.trim() || undefined,
  }
}

/** Whether a photo still belongs in the list after it was liked or hidden. */
function matches(p: AdminPhoto, f: Filters) {
  const category = f.category === '' || (f.category === 'none' ? p.categoryId === null : p.categoryId === f.category)
  return (f.show !== 'liked' || p.liked) && (f.show !== 'hidden' || p.hidden) && category
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
  /** Which gallery this list shows (default: the guest gallery). */
  gallery?: Gallery
  /** The event's categories, for the filter, labels and "Move to". */
  categories: Category[]
  /** Photos moved between categories: refresh the counts. */
  onCategoriesChanged: () => void
  onCoverChange: (id: string) => void
  /** The event changed on the server (e.g. hiding the cover photo cleared the cover). */
  onEventChanged: () => void
  /** Photo counts changed (delete / hide): refresh the dashboard numbers. */
  onPhotosChanged: () => void
}

export function PhotosPanel({ eventId, eventSlug, eventName, coverPhotoId, refreshKey, uploaders, gallery = 'GUEST', categories, onCategoriesChanged, onCoverChange, onEventChanged, onPhotosChanged }: Props) {
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [photos, setPhotos] = useState<AdminPhoto[]>([])
  const [hasMore, setHasMore] = useState(false)
  const [loading, setLoading] = useState(true)
  const [facets, setFacets] = useState<PhotoFacets | null>(null)
  const [viewerId, setViewerId] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [menuId, setMenuId] = useState<string | null>(null)
  // The menu that was last open stays for its fade-out after closing.
  const [lastMenuId, setLastMenuId] = useState<string | null>(null)
  if (menuId && menuId !== lastMenuId) setLastMenuId(menuId)
  const menu = usePresence(menuId !== null, 130)
  const shownMenuId = menu.mounted ? (menuId ?? lastMenuId) : null
  const [confirm, confirmDialog] = useConfirm()
  const loadingMore = useRef(false)
  // Select mode: tick photos, then move them to a category or download them in one go.
  const [selecting, setSelecting] = useState(false)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const categoryName = useMemo(() => new Map(categories.map((c) => [c.id, c.name])), [categories])

  // The More menu closes on a click/tap anywhere outside it, or Escape.
  useEffect(() => {
    if (!menuId) return
    const onDown = (e: PointerEvent) => {
      if (!(e.target as Element).closest(`[data-menu="${menuId}"]`)) setMenuId(null)
    }
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setMenuId(null)
    document.addEventListener('pointerdown', onDown)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onDown)
      document.removeEventListener('keydown', onKey)
    }
  }, [menuId])

  const cameraName = useMemo(() => new Map(uploaders.map((u) => [u.id, u.name])), [uploaders])

  const loadFacets = useCallback(() => adminApi.photoFacets(eventId, gallery).then(setFacets).catch(() => {}), [eventId, gallery])

  // A new filter or sort starts the list from the top.
  useEffect(() => {
    let active = true
    adminApi
      .photos(eventId, { ...toQuery(filters), gallery, offset: 0, limit: PAGE })
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
  }, [eventId, filters, gallery])

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
      .photos(eventId, { ...toQuery(filters), gallery, offset: 0, limit: PAGE })
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

  // Load the next page automatically when the end of the list scrolls into view.
  const sentinel = useRef<HTMLDivElement>(null)
  const moreRef = useRef<() => void>(() => {})
  useEffect(() => {
    const node = sentinel.current
    if (!node || !hasMore) return
    const observer = new IntersectionObserver((entries) => entries.some((e) => e.isIntersecting) && moreRef.current(), {
      rootMargin: '800px 0px',
    })
    observer.observe(node)
    return () => observer.disconnect()
  }, [hasMore, photos.length])

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
      const page = await adminApi.photos(eventId, { ...toQuery(filters), gallery, offset: photos.length, limit: PAGE })
      setPhotos((list) => {
        const known = new Set(list.map((p) => p.id))
        return [...list, ...page.photos.filter((p) => !known.has(p.id))]
      })
      setHasMore(page.nextOffset !== null)
    } finally {
      loadingMore.current = false
    }
  }, [eventId, gallery, filters, hasMore, photos.length])
  useEffect(() => {
    moreRef.current = more
  }, [more])

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

  // Optimistic: the dashboard changes at once; the (small, sometimes slow) server catches up in the background.
  const toggleHidden = async (p: AdminPhoto) => {
    const hidden = !p.hidden
    apply({ ...p, hidden })
    setNote(hidden ? 'Hidden from the guest gallery' : 'Back in the guest gallery')
    try {
      await adminApi.updatePhoto(p.id, { hidden })
      loadFacets()
      onPhotosChanged()
      if (hidden && p.id === coverPhotoId) onEventChanged()
    } catch {
      apply(p)
      setNote('Could not update the photo. Please try again.')
    }
  }

  const remove = async (p: AdminPhoto) => {
    const ok = await confirm({
      title: 'Delete this photo?',
      body: `${p.originalFileName} is removed from the gallery, including from guests' open screens. This cannot be undone.`,
      confirmLabel: 'Delete photo',
      danger: true,
    })
    if (!ok) return
    const index = photos.findIndex((x) => x.id === p.id)
    setPhotos((list) => list.filter((x) => x.id !== p.id))
    setNote('Photo deleted')
    try {
      await adminApi.deletePhoto(p.id)
      loadFacets()
      onPhotosChanged()
      if (p.id === coverPhotoId) onEventChanged()
    } catch {
      // Put it back where it was.
      setPhotos((list) => (list.some((x) => x.id === p.id) ? list : [...list.slice(0, index), p, ...list.slice(index)]))
      setNote('Could not delete the photo. Please try again.')
    }
  }

  const download = async (p: AdminPhoto) => {
    if (!p.webUrl) return
    setNote('Downloading…')
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
  const filtered = filters.uploaderId !== '' || filters.show !== 'all' || filters.day !== '' || filters.category !== '' || filters.q !== ''

  const togglePick = (id: string) =>
    setPicked((s) => {
      const next = new Set(s)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  const stopSelecting = () => {
    setSelecting(false)
    setPicked(new Set())
  }

  /** The ticked photos as one ZIP (originals where kept), with a live percentage. */
  const zip = useZipDownload()
  const zipping = zip.busy
  const downloadPicked = () => {
    const ids = [...picked]
    if (ids.length === 0) return
    void zip.start(() => adminApi.prepareZip(eventId, { photoIds: ids }))
  }

  /** Optimistic: labels change at once, the server follows. */
  const moveTo = async (categoryId: string | null) => {
    const ids = [...picked]
    if (ids.length === 0) return
    const before = photos
    setPhotos((list) => list.map((p) => (picked.has(p.id) ? { ...p, categoryId } : p)).filter((p) => matches(p, filters)))
    stopSelecting()
    setNote(`Moved ${formatCount(ids.length)} ${ids.length === 1 ? 'photo' : 'photos'} to ${categoryId ? categoryName.get(categoryId) : 'Not sorted'}`)
    try {
      await adminApi.assignCategory(eventId, ids, categoryId)
      onCategoriesChanged()
    } catch {
      setPhotos(before)
      setNote('Could not move the photos. Please try again.')
    }
  }

  const pickedPhotos = photos.filter((p) => picked.has(p.id))
  const allLiked = pickedPhotos.length > 0 && pickedPhotos.every((p) => p.liked)
  const allHidden = pickedPhotos.length > 0 && pickedPhotos.every((p) => p.hidden)
  const allShownPicked = photos.length > 0 && photos.every((p) => picked.has(p.id))
  const plural = (n: number) => `${formatCount(n)} ${n === 1 ? 'photo' : 'photos'}`

  /** Like / unlike, hide / show or delete every ticked photo in one request. Optimistic, like single changes. */
  const bulk = async (action: BulkPhotoAction) => {
    const ids = [...picked]
    if (ids.length === 0) return
    if (action === 'DELETE') {
      const ok = await confirm({
        title: `Delete ${plural(ids.length)}?`,
        body: "They are removed from the gallery, including from guests' open screens. This cannot be undone.",
        confirmLabel: `Delete ${plural(ids.length)}`,
        danger: true,
      })
      if (!ok) return
    }
    const before = photos
    const change = (p: AdminPhoto): AdminPhoto =>
      action === 'LIKE' || action === 'UNLIKE' ? { ...p, liked: action === 'LIKE' } : { ...p, hidden: action === 'HIDE' }
    setPhotos((list) => (action === 'DELETE'
      ? list.filter((p) => !picked.has(p.id))
      : list.map((p) => (picked.has(p.id) ? change(p) : p)).filter((p) => matches(p, filters))))
    const done = { LIKE: 'Liked', UNLIKE: 'Unliked', HIDE: 'Hidden from the guest gallery:', SHOW: 'Back in the guest gallery:', DELETE: 'Deleted' }[action]
    setNote(`${done} ${plural(ids.length)}`)
    if (action === 'DELETE') stopSelecting()
    try {
      await adminApi.bulkPhotos(eventId, ids, action)
      loadFacets()
      if (action !== 'LIKE' && action !== 'UNLIKE') {
        onPhotosChanged()
        if (coverPhotoId && ids.includes(coverPhotoId)) onEventChanged()
      }
    } catch {
      setPhotos(before)
      setNote('Could not change the photos. Please try again.')
    }
  }

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
  // File-name search waits for a pause in typing.
  const [search, setSearch] = useState('')
  const searchTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const onSearch = (value: string) => {
    setSearch(value)
    if (searchTimer.current) clearTimeout(searchTimer.current)
    searchTimer.current = setTimeout(() => set({ q: value }), 350)
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
          <option value="selected">✓ Couple's album selection</option>
        </select>
        <select aria-label="Day" className={selectClass} value={filters.day} onChange={(e) => set({ day: e.target.value })}>
          <option value="">All days</option>
          {days.map((d) => (
            <option key={d.day} value={d.day}>{formatEventDate(d.day)} ({formatCount(d.photos)})</option>
          ))}
        </select>
        <select aria-label="Category" className={selectClass} value={filters.category} onChange={(e) => set({ category: e.target.value })}>
          <option value="">All categories</option>
          {categories.map((c) => (
            <option key={c.id} value={c.id}>{c.name} ({formatCount(c.photos)})</option>
          ))}
          <option value="none">Not sorted yet</option>
        </select>
        <input type="search" aria-label="Search file name" placeholder="File name, e.g. IMG_1234" value={search}
          onChange={(e) => onSearch(e.target.value)} className={`${selectClass} w-44`} />
        <select aria-label="Sort" className={selectClass} value={filters.sort} onChange={(e) => set({ sort: e.target.value as AdminPhotoSort })}>
          <option value="NEWEST">Newest first</option>
          <option value="OLDEST">Oldest first</option>
          <option value="CAMERA">By camera</option>
        </select>
        {filtered && (
          <Button variant="ghost" onClick={() => {
            setSearch('')
            set({ ...NO_FILTERS, sort: filters.sort })
          }}>Clear filters</Button>
        )}
        <span className="flex-1" />
        {!selecting && photos.length > 0 && <Button onClick={() => setSelecting(true)}>Select</Button>}
      </div>

      <ZipProgressCard status={zip.status} onClose={zip.dismiss} />
      {selecting && (
        <div className="anim-sheet-up sticky top-14 z-20 mb-4 flex flex-wrap items-center gap-2 rounded-xl border border-line bg-white p-2.5 shadow-md" data-testid="select-bar">
          <span className="px-1 text-sm font-medium tabular-nums">{formatCount(picked.size)} selected</span>
          {!allShownPicked && <Button variant="ghost" onClick={() => setPicked(new Set(photos.map((p) => p.id)))}>Select all</Button>}
          {picked.size > 0 && <Button variant="ghost" onClick={() => setPicked(new Set())}>Clear</Button>}
          <span className="hidden h-6 w-px bg-line sm:block" aria-hidden="true" />
          <select
            aria-label="Move to category"
            className={selectClass}
            value=""
            disabled={picked.size === 0}
            onChange={(e) => e.target.value && moveTo(e.target.value === 'none' ? null : e.target.value)}
          >
            <option value="">Move to category…</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            <option value="none">Not sorted</option>
          </select>
          <Button onClick={() => bulk(allLiked ? 'UNLIKE' : 'LIKE')} disabled={picked.size === 0}>
            <HeartIcon filled={allLiked} /> {allLiked ? 'Unlike' : 'Like'}
          </Button>
          <Button onClick={() => bulk(allHidden ? 'SHOW' : 'HIDE')} disabled={picked.size === 0}>
            <EyeIcon off={!allHidden} /> {allHidden ? 'Show' : 'Hide'}
          </Button>
          <Button onClick={downloadPicked} disabled={picked.size === 0 || zipping} title="Download as one ZIP file">
            <DownloadIcon /> {zipping ? 'Downloading…' : 'Download'}
          </Button>
          <Button variant="danger" onClick={() => bulk('DELETE')} disabled={picked.size === 0}>
            <TrashIcon /> Delete
          </Button>
          <span className="flex-1" />
          <Button variant="primary" onClick={stopSelecting}>Done</Button>
        </div>
      )}

      {!loading && photos.length === 0 && <p className="text-sm text-muted">{filtered ? 'No photos match these filters.' : 'No photos yet.'}</p>}
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5" data-testid="admin-photos">
        {photos.map((p) => {
          const ready = p.status === 'READY' && !!p.webUrl
          return (
            <li key={p.id} className="min-w-0">
              <div className="relative aspect-square overflow-hidden rounded-lg bg-stone-100">
                {p.thumbnailUrl ? (
                  <button
                    type="button"
                    className="block size-full"
                    onClick={() => (selecting ? togglePick(p.id) : ready && setViewerId(p.id))}
                    aria-label={selecting ? `${picked.has(p.id) ? 'Unselect' : 'Select'} ${p.originalFileName}` : `View ${p.originalFileName}`}
                    aria-pressed={selecting ? picked.has(p.id) : undefined}
                  >
                    <img src={p.thumbnailUrl} alt="" loading="lazy" className={`size-full object-cover ${p.hidden ? 'opacity-45' : ''} ${selecting && picked.has(p.id) ? 'scale-90 rounded-md' : ''} transition-transform`} />
                    {selecting && (
                      <span className={`absolute top-1.5 left-1.5 flex size-6 items-center justify-center rounded-full border-2 text-xs text-white ${picked.has(p.id) ? 'border-ink bg-ink' : 'border-white bg-black/20'}`}>
                        {picked.has(p.id) ? '✓' : ''}
                      </span>
                    )}
                  </button>
                ) : (
                  <div className="flex size-full items-center justify-center text-xs text-muted">{statusLabel[p.status]}</div>
                )}
                <div className={`pointer-events-none absolute top-1.5 left-1.5 flex flex-col items-start gap-1 ${selecting ? 'hidden' : ''}`}>
                  {p.id === coverPhotoId && <span className="rounded bg-ink px-1.5 py-0.5 text-[11px] text-white">Cover</span>}
                  {p.hidden && <span className="inline-flex items-center gap-1 rounded bg-white/90 px-1.5 py-0.5 text-[11px]"><EyeIcon off className="size-3" />Hidden</span>}
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
                <div className="pointer-events-none absolute bottom-1.5 left-1.5 flex max-w-[calc(100%-0.75rem)] flex-wrap gap-1">
                  {p.selected && <span className="rounded bg-emerald-600 px-1.5 py-0.5 text-[11px] text-white">✓ Album</span>}
                  {p.categoryId && categoryName.get(p.categoryId) && (
                    <span className="truncate rounded bg-white/90 px-1.5 py-0.5 text-[11px]">{categoryName.get(p.categoryId)}</span>
                  )}
                </div>
                {p.status !== 'READY' && (
                  <span className={`absolute right-1.5 bottom-1.5 rounded px-1.5 py-0.5 text-[11px] ${p.status === 'FAILED' ? 'bg-red-600 text-white' : 'bg-white/90'}`}>
                    {statusLabel[p.status]}
                  </span>
                )}
              </div>
              <div className="mt-1 flex items-center gap-1">
                <p className="min-w-0 flex-1 truncate text-xs text-muted" title={p.failureReason ?? p.originalFileName}>
                  {p.status === 'FAILED' ? p.failureReason : p.originalFileName}
                  {p.uploaderId && cameraName.get(p.uploaderId) ? ` · ${cameraName.get(p.uploaderId)}` : ''}
                </p>
                {ready && (
                  <IconButton label={p.hidden ? 'Show in gallery' : 'Hide from gallery'} onClick={() => toggleHidden(p)}>
                    <EyeIcon off={!p.hidden} />
                  </IconButton>
                )}
                <div className="relative" data-menu={p.id}>
                  <IconButton label="More" expanded={menuId === p.id} onClick={() => setMenuId((id) => (id === p.id ? null : p.id))}>
                    <MoreIcon />
                  </IconButton>
                  {shownMenuId === p.id && (
                    <div role="menu" aria-hidden={menu.closing || undefined} inert={menu.closing}
                      className={`absolute right-0 z-20 mt-1 w-52 rounded-lg border border-line bg-white p-1 text-sm shadow-lg ${menu.closing ? 'anim-pop-out' : 'anim-pop-in'}`}>
                      {ready && (
                        <>
                          <MenuItem onClick={() => { setMenuId(null); download(p) }}><DownloadIcon />Download</MenuItem>
                          {p.originalAvailable && (
                            <MenuItem onClick={() => { setMenuId(null); original(p) }}><DownloadIcon />Download original</MenuItem>
                          )}
                          <MenuItem disabled={p.hidden || p.id === coverPhotoId} onClick={() => { setMenuId(null); makeCover(p) }}>
                            <StarIcon />{p.id === coverPhotoId ? 'Gallery cover' : 'Make cover'}
                          </MenuItem>
                          <div className="my-1 border-t border-line" />
                        </>
                      )}
                      <MenuItem danger onClick={() => { setMenuId(null); remove(p) }}><TrashIcon />Delete</MenuItem>
                    </div>
                  )}
                </div>
              </div>
            </li>
          )
        })}
      </ul>
      {confirmDialog}
      <div ref={sentinel} aria-hidden="true" />
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

/** A small icon-only button under a photo; the label is read out and shown on hover. */
function IconButton({ label, onClick, children, expanded }: { label: string; onClick: () => void; children: ReactNode; expanded?: boolean }) {
  return (
    <button type="button" title={label} aria-label={label} aria-expanded={expanded} onClick={onClick}
      className="flex size-9 shrink-0 items-center justify-center rounded-md text-stone-600 hover:bg-stone-100 hover:text-ink">
      {children}
    </button>
  )
}

function MenuItem({ onClick, disabled, danger, children }: { onClick: () => void; disabled?: boolean; danger?: boolean; children: ReactNode }) {
  return (
    <button type="button" role="menuitem" disabled={disabled} onClick={onClick} className={`flex w-full items-center gap-2 rounded-md px-2.5 py-2 text-left disabled:opacity-40 disabled:hover:bg-transparent ${danger ? 'text-red-700 hover:bg-red-50' : 'hover:bg-stone-50'}`}>
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
