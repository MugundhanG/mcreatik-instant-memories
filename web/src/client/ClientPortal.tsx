import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { PhotoViewer } from '../gallery/PhotoViewer'
import { ApiError } from '../lib/api'
import { formatCount, formatEventDate } from '../lib/format'
import { downloadPhoto } from '../lib/photoActions'
import type { PublicPhoto } from '../lib/types'
import { resolveBrand } from '../shared/brand'
import { GalleryUnavailable } from '../shared/GalleryUnavailable'
import { PageLoader, ShutterLoader } from '../shared/ShutterLoader'
import { useZipDownload } from '../shared/zipDownload'
import { ZipProgressCard } from '../shared/ZipProgressCard'
import { clientApi, clientSession, type ClientMe, type ClientPhoto } from './clientApi'

const PAGE = 60
/** The server's limit for one ZIP. */
const MAX_ZIP = 1000
/** 'all' | a category id | 'none' (not sorted) | 'album' (the couple's selection) */
type Tab = string

const toViewer = (p: ClientPhoto): PublicPhoto => ({
  id: p.id, thumbnailUrl: p.thumbnailUrl, webUrl: p.webUrl, width: p.width ?? 0, height: p.height ?? 0, readyAt: '', cursor: p.id,
})

function Check({ on }: { on: boolean }) {
  return (
    <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="2.6" aria-hidden="true">
      {on ? <path d="m5 12.5 4.5 4.5L19 7.5" /> : <path d="M12 5v14M5 12h14" />}
    </svg>
  )
}

/** The couple's portal: every photo by category, originals to download, and their album selection. */
export default function ClientPortal() {
  const [me, setMe] = useState<ClientMe | null>(null)
  const [tab, setTab] = useState<Tab>('all')
  const [photos, setPhotos] = useState<ClientPhoto[]>([])
  const [nextOffset, setNextOffset] = useState<number | null>(0)
  const [loading, setLoading] = useState(true)
  const [viewerId, setViewerId] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const loadingMore = useRef(false)
  // Select mode: tick several photos, then download them as one ZIP.
  const [picking, setPicking] = useState(false)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const zip = useZipDownload()
  const zipping = zip.busy

  // McreatiK may have turned this gallery off: show why instead of loading forever.
  const [closed, setClosed] = useState<string | null>(null)
  const loadMe = useCallback(() => clientApi.me().then(setMe).catch((err) => {
    if (err instanceof ApiError && (err.code === 'GALLERY_UNAVAILABLE' || err.code === 'GALLERY_EXPIRED')) setClosed(err.message)
  }), [])
  useEffect(() => {
    loadMe()
  }, [loadMe])

  useEffect(() => {
    if (!toast) return
    const t = setTimeout(() => setToast(null), 3500)
    return () => clearTimeout(t)
  }, [toast])

  const query = useCallback((offset: number) => clientApi.photos({
    category: tab === 'all' || tab === 'album' ? undefined : tab, selected: tab === 'album', offset, limit: PAGE,
  }), [tab])

  // A new tab starts from the first photo.
  useEffect(() => {
    let active = true
    query(0).then((page) => {
      if (!active) return
      setPhotos(page.photos)
      setNextOffset(page.nextOffset)
    }).catch(() => {}).finally(() => active && setLoading(false))
    return () => {
      active = false
    }
  }, [query])

  const more = useCallback(async () => {
    if (loadingMore.current || nextOffset === null) return
    loadingMore.current = true
    try {
      const page = await query(photos.length)
      setPhotos((list) => [...list, ...page.photos.filter((p) => !list.some((x) => x.id === p.id))])
      setNextOffset(page.nextOffset)
    } finally {
      loadingMore.current = false
    }
  }, [query, nextOffset, photos.length])

  const sentinel = useRef<HTMLDivElement>(null)
  const moreRef = useRef(more)
  useEffect(() => {
    moreRef.current = more
  }, [more])
  useEffect(() => {
    const node = sentinel.current
    if (!node || nextOffset === null) return
    const io = new IntersectionObserver((e) => e.some((x) => x.isIntersecting) && moreRef.current(), { rootMargin: '1000px 0px' })
    io.observe(node)
    return () => io.disconnect()
  }, [nextOffset, photos.length])

  const stopPicking = () => {
    setPicking(false)
    setPicked(new Set())
  }
  const togglePick = (id: string) => setPicked((set) => {
    const next = new Set(set)
    if (next.has(id)) next.delete(id)
    else next.add(id)
    return next
  })

  /** Several photos as one ZIP file (originals where the studio kept them), with a live percentage. */
  const downloadZip = (input: { album?: boolean; photoIds?: string[]; category?: string }) => {
    stopPicking()
    void zip.start(() => clientApi.prepareZip(input))
  }

  const changeTab = (t: Tab) => {
    if (t === tab) return
    setLoading(true)
    setPhotos([])
    setTab(t)
    window.scrollTo({ top: 0 })
  }

  /** Optimistic: the tick changes at once; the album limit is checked by the server. */
  const toggle = async (p: ClientPhoto) => {
    const selected = !p.selected
    setPhotos((list) => list.map((x) => (x.id === p.id ? { ...x, selected } : x)).filter((x) => tab !== 'album' || x.selected || x.id === viewerId))
    setMe((m) => (m ? { ...m, selectedCount: m.selectedCount + (selected ? 1 : -1) } : m))
    try {
      const res = await clientApi.select(p.id, selected)
      setMe((m) => (m ? { ...m, selectedCount: res.selectedCount, albumLimit: res.albumLimit } : m))
    } catch (err) {
      setPhotos((list) => list.map((x) => (x.id === p.id ? { ...x, selected: p.selected } : x)))
      loadMe()
      setToast(err instanceof ApiError ? err.message : 'Could not save your choice. Please try again.')
    }
  }

  const download = async (p: ClientPhoto) => {
    setToast('Preparing download…')
    try {
      const { url, original } = await clientApi.download(p.id)
      if (original) {
        window.location.assign(url) // the signed link downloads the file without leaving this page
        setToast('Downloading the original…')
      } else {
        const result = await downloadPhoto(url, 'photo', p.id)
        setToast(result === 'saved' || result === 'save-sheet' ? 'Saved ✓' : 'Press and hold the photo, then tap “Save image”')
      }
    } catch {
      setToast('Could not download this photo')
    }
  }

  const viewerPhotos = useMemo(() => photos.map(toViewer), [photos])
  const viewerIndex = viewerId ? photos.findIndex((p) => p.id === viewerId) : -1
  const byId = useMemo(() => new Map(photos.map((p) => [p.id, p])), [photos])

  if (closed) {
    return (
      <GalleryUnavailable message={closed}>
        <button type="button" onClick={clientSession.signOut} className="mt-6 rounded-full border border-ink px-5 py-2 text-sm">Log out</button>
      </GalleryUnavailable>
    )
  }
  if (!me) return <PageLoader label="Loading your photos" />
  const brand = resolveBrand(me.event.brand)
  const tabs: { id: Tab; label: string; count: number }[] = [
    { id: 'all', label: 'All', count: me.totalPhotos },
    ...me.categories.filter((c) => c.photos > 0).map((c) => ({ id: c.id, label: c.name, count: c.photos })),
    ...(me.uncategorizedPhotos > 0 && me.uncategorizedPhotos < me.totalPhotos ? [{ id: 'none', label: 'More', count: me.uncategorizedPhotos }] : []),
    { id: 'album', label: '✓ My album', count: me.selectedCount },
  ]
  const full = me.albumLimit !== null && me.selectedCount >= me.albumLimit
  const current = tabs.find((t) => t.id === tab) ?? tabs[0]
  const downloadTab = () => downloadZip(tab === 'album' ? { album: true } : { category: tab })

  return (
    <div className="min-h-dvh bg-paper">
      <header className="relative overflow-hidden bg-[#2e211a] text-white">
        {me.event.coverUrl && <img src={me.event.coverUrl} alt="" className="absolute inset-0 size-full object-cover opacity-55" />}
        <div className="absolute inset-0 bg-[linear-gradient(180deg,rgba(0,0,0,0.25),rgba(0,0,0,0.75))]" />
        <div className="relative mx-auto flex max-w-6xl flex-col gap-6 px-5 pt-5 pb-8">
          <div className="flex items-center justify-between gap-3">
            {brand.heroLogo
              ? <span className={brand.heroLogoOnCard ? 'rounded-xl bg-white/90 p-1.5' : ''}><img src={brand.heroLogo} alt={brand.name} className="h-10 w-auto max-w-40 object-contain" /></span>
              : <span className="text-xs font-semibold tracking-[0.3em] uppercase">{brand.name}</span>}
            <button type="button" onClick={clientSession.signOut} className="rounded-full bg-white/15 px-3 py-1.5 text-xs backdrop-blur hover:bg-white/25">Log out</button>
          </div>
          <div>
            <p className="text-[11px] font-semibold tracking-[0.35em] text-[#f3dfb4] uppercase">{brand.name}</p>
            <h1 className="mt-1 font-display text-4xl leading-tight font-medium sm:text-5xl">{me.event.name}</h1>
            <p className="mt-1 text-sm text-white/80">{formatEventDate(me.event.eventDate)} · Welcome, {me.name}</p>
          </div>
        </div>
      </header>

      <div className="sticky top-0 z-30 border-b border-line bg-paper/95 backdrop-blur">
        <div className="mx-auto max-w-6xl px-3">
          <div className="flex items-center justify-between gap-3 px-1 pt-3 text-sm">
            <p className="text-muted">Tap <span className="inline-flex size-5 translate-y-1 items-center justify-center rounded-full bg-ink text-white"><Check on={false} /></span> to choose photos for your album</p>
            <p className="shrink-0 font-medium tabular-nums" data-testid="album-counter">
              {formatCount(me.selectedCount)}{me.albumLimit !== null ? ` / ${formatCount(me.albumLimit)}` : ''} selected
            </p>
          </div>
          <nav className="flex gap-2 overflow-x-auto py-3 [scrollbar-width:none]" aria-label="Categories">
            {tabs.map((t) => (
              <button key={t.id} type="button" onClick={() => changeTab(t.id)} aria-pressed={tab === t.id}
                className={`shrink-0 rounded-full px-4 py-2 text-sm whitespace-nowrap ${tab === t.id ? 'bg-ink text-white' : 'border border-line bg-white text-ink hover:bg-stone-50'}`}>
                {t.label} <span className={tab === t.id ? 'text-white/70' : 'text-muted'}>{formatCount(t.count)}</span>
              </button>
            ))}
          </nav>
        </div>
      </div>

      <main className="mx-auto max-w-6xl px-3 py-4">
        {!loading && photos.length > 0 && (
          <div className="mb-3 flex flex-wrap items-center gap-2" data-testid="download-bar">
            {picking ? (
              <>
                <span className="px-1 text-sm font-medium tabular-nums">{formatCount(picked.size)} picked</span>
                <button type="button" onClick={() => downloadZip({ photoIds: [...picked] })} disabled={picked.size === 0 || zipping}
                  className="rounded-full bg-ink px-4 py-2 text-sm font-medium text-white disabled:opacity-40">
                  Download {picked.size > 0 ? formatCount(picked.size) : ''} (.zip)
                </button>
                <button type="button" onClick={() => setPicked(new Set(photos.map((p) => p.id)))} className="rounded-full border border-line bg-white px-4 py-2 text-sm">Pick all shown</button>
                <button type="button" onClick={stopPicking} className="rounded-full px-3 py-2 text-sm text-muted hover:text-ink">Cancel</button>
              </>
            ) : (
              <>
                <button type="button" onClick={downloadTab} disabled={zipping || current.count > MAX_ZIP}
                  className="rounded-full bg-ink px-4 py-2 text-sm font-medium text-white disabled:opacity-40">
                  {tab === 'album' ? `Download my album (${formatCount(current.count)})` : `Download all ${formatCount(current.count)}${tab === 'all' ? '' : ` in ${current.label}`}`}
                </button>
                <button type="button" onClick={() => setPicking(true)} className="rounded-full border border-line bg-white px-4 py-2 text-sm">Select photos to download</button>
                {current.count > MAX_ZIP && <span className="text-xs text-muted">Too many for one file: download by category.</span>}
              </>
            )}
          </div>
        )}
        {loading && <ShutterLoader className="py-20" />}
        {!loading && photos.length === 0 && (
          <p className="py-20 text-center text-muted">{tab === 'album' ? 'No photos selected yet. Tap the + on photos you love.' : 'No photos here yet.'}</p>
        )}
        <ul className="grid grid-cols-3 gap-1.5 sm:grid-cols-4 lg:grid-cols-6" data-testid="client-photos">
          {photos.map((p) => (
            <li key={p.id} className="relative aspect-square overflow-hidden rounded-md bg-stone-100">
              <button type="button" className="block size-full" onClick={() => (picking ? togglePick(p.id) : setViewerId(p.id))}
                aria-label={picking ? (picked.has(p.id) ? 'Unpick photo' : 'Pick photo') : 'Open photo'} aria-pressed={picking ? picked.has(p.id) : undefined}>
                <img src={p.thumbnailUrl} alt="" loading="lazy" className={`size-full object-cover transition-transform ${picking && picked.has(p.id) ? 'scale-90 rounded-md' : ''}`} />
              </button>
              {picking && (
                <span className={`pointer-events-none absolute top-1.5 left-1.5 flex size-6 items-center justify-center rounded-full border-2 ${picked.has(p.id) ? 'border-ink bg-ink text-white' : 'border-white bg-black/20'}`} aria-hidden="true">
                  {picked.has(p.id) && <Check on />}
                </span>
              )}
              {!picking && <button
                type="button"
                onClick={() => toggle(p)}
                aria-pressed={p.selected}
                aria-label={p.selected ? 'Remove from album' : 'Add to album'}
                disabled={!p.selected && full}
                className={`absolute right-1.5 bottom-1.5 flex size-8 items-center justify-center rounded-full shadow-md transition-colors disabled:opacity-40 ${p.selected ? 'bg-emerald-600 text-white' : 'bg-white/90 text-ink'}`}
              >
                <Check on={p.selected} />
              </button>}
            </li>
          ))}
        </ul>
        <div ref={sentinel} aria-hidden="true" />
        {nextOffset !== null && !loading && <ShutterLoader label="Loading more" className="py-6 [&_svg]:size-8" />}
        <p className="py-8 text-center text-xs text-muted"><a href="/privacy" className="underline underline-offset-2 hover:text-ink">Privacy &amp; security</a></p>
      </main>

      <ZipProgressCard status={zip.status} onClose={zip.dismiss} />
      {toast && !zip.status && (
        <div role="status" className="anim-sheet-up fixed bottom-6 left-1/2 z-[70] w-max max-w-[calc(100%-2rem)] -translate-x-1/2 rounded-2xl bg-ink px-4 py-2.5 text-center text-sm text-white shadow-lg">{toast}</div>
      )}

      {viewerIndex >= 0 && (
        <PhotoViewer
          photos={viewerPhotos}
          index={viewerIndex}
          total={tab === 'all' ? me.totalPhotos : viewerPhotos.length}
          eventSlug="photo"
          eventName={me.event.name}
          promoteFollow={false}
          hideDownload
          onIndexChange={(i) => setViewerId(photos[i].id)}
          onClose={() => setViewerId(null)}
          onNearEnd={more}
          actions={(vp) => {
            const p = byId.get(vp.id)
            if (!p) return null
            return (
              <>
                <button type="button" onClick={() => download(p)}
                  className="inline-flex h-12 items-center justify-center gap-2 rounded-full border border-white/30 px-5 text-sm font-medium text-white hover:bg-white/10">
                  {p.originalAvailable ? 'Download original' : 'Download'}
                </button>
                <button type="button" onClick={() => toggle(p)} aria-pressed={p.selected} disabled={!p.selected && full}
                  className={`inline-flex h-12 items-center justify-center gap-2 rounded-full px-5 text-sm font-medium disabled:opacity-50 ${p.selected ? 'bg-emerald-600 text-white' : 'bg-white text-ink'}`}>
                  <Check on={p.selected} />{p.selected ? 'In my album' : 'Add to album'}
                </button>
              </>
            )
          }}
        />
      )}
    </div>
  )
}
