import { useCallback, useEffect, useReducer, useRef } from 'react'
import { ApiError, publicApi } from '../lib/api'
import type { EventStatus, PhotoReadyMessage, PublicEvent, PublicPhoto, PublicPhotoPage } from '../lib/types'

/** 'unavailable': McreatiK has turned this gallery off (see `unavailableMessage`). */
export type LoadStatus = 'loading' | 'ready' | 'not-found' | 'unavailable' | 'error'
export type Connection = 'connecting' | 'live' | 'reconnecting'

export interface LiveGalleryState {
  event: PublicEvent | null
  /** Shown in the grid, newest first. */
  photos: PublicPhoto[]
  /** Arrived while the guest was scrolled down / viewing a photo; revealed on tap. */
  incoming: PublicPhoto[]
  nextCursor: string | null
  status: LoadStatus
  /** Why the gallery is unavailable, naming the studio to contact. */
  unavailableMessage?: string
  connection: Connection
  loadingMore: boolean
}

type Action =
  | { type: 'loaded'; event: PublicEvent; page: PublicPhotoPage }
  | { type: 'failed'; status: LoadStatus; message?: string }
  | { type: 'arrived'; photos: PublicPhoto[]; show: boolean }
  | { type: 'showIncoming' }
  | { type: 'removed'; id: string }
  | { type: 'reconciled'; photos: PublicPhoto[]; complete: boolean }
  | { type: 'loadingMore' }
  | { type: 'moreLoaded'; page: PublicPhotoPage }
  | { type: 'moreFailed' }
  | { type: 'eventStatus'; status: EventStatus; live: boolean }
  | { type: 'connection'; connection: Connection }

const initialState: LiveGalleryState = {
  event: null,
  photos: [],
  incoming: [],
  nextCursor: null,
  status: 'loading',
  connection: 'connecting',
  loadingMore: false,
}

const readyTime = (p: PublicPhoto) => Date.parse(p.readyAt)

/** Newest first, ties broken by id (matches the server's ordering). */
export function byNewest(a: PublicPhoto, b: PublicPhoto) {
  return readyTime(b) - readyTime(a) || (a.id < b.id ? 1 : a.id > b.id ? -1 : 0)
}

export function reducer(state: LiveGalleryState, action: Action): LiveGalleryState {
  switch (action.type) {
    case 'loaded':
      return { ...state, event: action.event, photos: action.page.photos, incoming: [], nextCursor: action.page.nextCursor, status: 'ready' }
    case 'failed':
      return { ...state, status: action.status, unavailableMessage: action.message }
    case 'arrived': {
      const known = new Set([...state.photos, ...state.incoming].map((p) => p.id))
      const fresh = action.photos.filter((p) => !known.has(p.id))
      if (fresh.length === 0) return state
      const event = state.event ? { ...state.event, photoCount: state.event.photoCount + fresh.length } : state.event
      if (action.show) {
        return { ...state, event, photos: [...fresh, ...state.incoming, ...state.photos].sort(byNewest), incoming: [] }
      }
      return { ...state, event, incoming: [...fresh, ...state.incoming].sort(byNewest) }
    }
    case 'showIncoming':
      if (state.incoming.length === 0) return state
      return { ...state, photos: [...state.incoming, ...state.photos].sort(byNewest), incoming: [] }
    case 'removed': {
      const photos = state.photos.filter((p) => p.id !== action.id)
      const incoming = state.incoming.filter((p) => p.id !== action.id)
      const removed = state.photos.length + state.incoming.length - photos.length - incoming.length
      const event = state.event ? { ...state.event, photoCount: Math.max(0, state.event.photoCount - removed) } : null
      return { ...state, photos, incoming, event }
    }
    case 'reconciled': {
      // `photos` is the server's current first page. Whatever we hold inside its time range that it no longer returns
      // was hidden or deleted while this phone missed the live stream. `complete`: it was the whole gallery.
      if (action.photos.length === 0 && !action.complete) return state
      const keep = new Set(action.photos.map((p) => p.id))
      const oldest = action.photos.length > 0 ? action.photos[action.photos.length - 1] : null
      const stale = (p: PublicPhoto) => !keep.has(p.id) && (action.complete || (oldest !== null && byNewest(p, oldest) <= 0))
      const photos = state.photos.filter((p) => !stale(p))
      const incoming = state.incoming.filter((p) => !stale(p))
      const removed = state.photos.length + state.incoming.length - photos.length - incoming.length
      if (removed === 0) return state
      const event = state.event ? { ...state.event, photoCount: Math.max(0, state.event.photoCount - removed) } : null
      return { ...state, photos, incoming, event }
    }
    case 'loadingMore':
      return { ...state, loadingMore: true }
    case 'moreLoaded': {
      const known = new Set(state.photos.map((p) => p.id))
      return {
        ...state,
        loadingMore: false,
        photos: [...state.photos, ...action.page.photos.filter((p) => !known.has(p.id))],
        nextCursor: action.page.nextCursor,
      }
    }
    case 'moreFailed':
      return { ...state, loadingMore: false }
    case 'eventStatus':
      return state.event ? { ...state, event: { ...state.event, status: action.status, live: action.live } } : state
    case 'connection':
      return state.connection === action.connection ? state : { ...state, connection: action.connection }
  }
}

function fromMessage(m: PhotoReadyMessage): PublicPhoto {
  return { id: m.photoId, thumbnailUrl: m.thumbnailUrl, webUrl: m.webUrl, width: m.width, height: m.height, readyAt: m.readyAt, cursor: m.cursor, categoryId: m.categoryId ?? null }
}

/** Not found, turned off by McreatiK (with the server's message), or another error. */
function failure(e: unknown): Action {
  if (e instanceof ApiError && (e.code === 'GALLERY_UNAVAILABLE' || e.code === 'GALLERY_EXPIRED')) return { type: 'failed', status: 'unavailable', message: e.message }
  return { type: 'failed', status: e instanceof ApiError && e.status === 404 ? 'not-found' : 'error' }
}

const SAFETY_POLL_MS = 60_000
const RECONNECT_MS = 5_000

/**
 * Loads an event gallery and keeps it live over Server-Sent Events.
 * - PHOTO_READY inserts photos without a refresh.
 * - After any disconnect (flaky venue Wi-Fi, phone asleep) it fetches everything newer than the
 *   newest photo it has, so nothing is ever missed.
 * - A slow safety poll covers proxies that silently kill streams.
 *
 * @param shouldShowImmediately decides per arrival whether to insert now (guest at the top) or hold
 *        photos behind a "new photos" button so the grid doesn't jump under the guest's finger.
 */
export function useLiveGallery(slug: string, shouldShowImmediately: () => boolean, category: string | null = null) {
  const [state, dispatch] = useReducer(reducer, initialState)
  const newest = useRef<PublicPhoto | null>(null)
  const showRef = useRef(shouldShowImmediately)
  const categoryRef = useRef(category)
  useEffect(() => {
    showRef.current = shouldShowImmediately
    categoryRef.current = category
  })

  const trackNewest = useCallback((photos: PublicPhoto[]) => {
    for (const p of photos) {
      if (!newest.current || byNewest(p, newest.current) < 0) newest.current = p
    }
  }, [])

  const arrive = useCallback(
    (all: PublicPhoto[]) => {
      const photos = categoryRef.current ? all.filter((p) => p.categoryId === categoryRef.current) : all
      if (photos.length === 0) return
      trackNewest(photos)
      dispatch({ type: 'arrived', photos, show: showRef.current() })
    },
    [trackNewest],
  )

  // Initial load
  useEffect(() => {
    let cancelled = false
    newest.current = null
    // A private gallery never lists its photos (guests see only their own, through Find my photos).
    const firstPage = publicApi.photos(slug, { limit: 40, category }).catch((e) => {
      if (e instanceof ApiError && e.code === 'PRIVATE_GALLERY') return { photos: [], nextCursor: null } as PublicPhotoPage
      throw e
    })
    Promise.all([publicApi.event(slug), firstPage])
      .then(([event, page]) => {
        if (cancelled) return
        trackNewest(page.photos)
        dispatch({ type: 'loaded', event, page })
      })
      .catch((e) => {
        if (!cancelled) dispatch(failure(e))
      })
    return () => {
      cancelled = true
    }
  }, [slug, category, trackNewest])

  const catchUp = useCallback(async (reconcile = true) => {
    try {
      const page = newest.current
        ? await publicApi.photos(slug, { after: newest.current.cursor, limit: 200, category: categoryRef.current })
        : await publicApi.photos(slug, { limit: 40, category: categoryRef.current })
      arrive(page.photos)
      if (reconcile) {
        // Photos hidden or deleted while this phone missed the stream: drop what the first page no longer returns.
        const first = await publicApi.photos(slug, { limit: 40, category: categoryRef.current })
        dispatch({ type: 'reconciled', photos: first.photos, complete: first.nextCursor === null })
      }
    } catch (e) {
      // Turned off by McreatiK while open: show why. Anything else: the next reconnect / poll tries again.
      if (e instanceof ApiError && (e.code === 'GALLERY_UNAVAILABLE' || e.code === 'GALLERY_EXPIRED')) dispatch(failure(e))
      // Made private while open: reload into the private page (Find my photos only).
      if (e instanceof ApiError && e.code === 'PRIVATE_GALLERY') window.location.reload()
    }
  }, [slug, arrive])

  // Live stream
  const loaded = state.status === 'ready' && !state.event?.privateGallery
  useEffect(() => {
    if (!loaded || typeof EventSource === 'undefined') return
    let source: EventSource | null = null
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined
    let hadError = false
    let disposed = false

    const connect = () => {
      source = new EventSource(publicApi.streamUrl(slug))
      source.addEventListener('CONNECTED', () => {
        dispatch({ type: 'connection', connection: 'live' })
        if (hadError) void catchUp()
        hadError = false
      })
      source.addEventListener('PHOTO_READY', (e) => {
        try {
          arrive([fromMessage(JSON.parse((e as MessageEvent).data))])
        } catch {
          // malformed message; the next catch-up will fix it
        }
      })
      source.addEventListener('PHOTO_REMOVED', (e) => {
        try {
          dispatch({ type: 'removed', id: JSON.parse((e as MessageEvent).data).photoId })
        } catch {
          // ignore
        }
      })
      source.addEventListener('GALLERY_CLOSED', (e) => {
        // Turned off by McreatiK. Leaving 'ready' ends this stream and the polling (the effect cleans up).
        let message = 'Please contact your photographer.'
        try {
          message = JSON.parse((e as MessageEvent).data).message ?? message
        } catch {
          // keep the default
        }
        dispatch({ type: 'failed', status: 'unavailable', message })
      })
      source.addEventListener('EVENT_STATUS', (e) => {
        try {
          const data = JSON.parse((e as MessageEvent).data)
          dispatch({ type: 'eventStatus', status: data.status, live: data.live })
        } catch {
          // ignore
        }
      })
      source.onerror = () => {
        hadError = true
        dispatch({ type: 'connection', connection: 'reconnecting' })
        // The browser retries on its own unless the stream was closed for good (e.g. HTTP error).
        if (source?.readyState === EventSource.CLOSED && !disposed) {
          source.close()
          reconnectTimer = setTimeout(connect, RECONNECT_MS)
        }
      }
    }
    connect()

    const poll = setInterval(() => void catchUp(false), SAFETY_POLL_MS)
    const onVisible = () => {
      if (document.visibilityState === 'visible') void catchUp()
    }
    document.addEventListener('visibilitychange', onVisible)

    return () => {
      disposed = true
      source?.close()
      clearTimeout(reconnectTimer)
      clearInterval(poll)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [loaded, slug, arrive, catchUp])

  const loadMore = useCallback(async () => {
    if (!state.nextCursor || state.loadingMore) return
    dispatch({ type: 'loadingMore' })
    try {
      dispatch({ type: 'moreLoaded', page: await publicApi.photos(slug, { before: state.nextCursor, limit: 40, category }) })
    } catch {
      dispatch({ type: 'moreFailed' })
    }
  }, [slug, category, state.nextCursor, state.loadingMore])

  const showIncoming = useCallback(() => dispatch({ type: 'showIncoming' }), [])

  return { ...state, loadMore, showIncoming }
}
