import { useCallback, useEffect, useReducer, useRef } from 'react'
import { ApiError, publicApi } from '../lib/api'
import type { EventStatus, PhotoReadyMessage, PublicEvent, PublicPhoto, PublicPhotoPage } from '../lib/types'

export type LoadStatus = 'loading' | 'ready' | 'not-found' | 'error'
export type Connection = 'connecting' | 'live' | 'reconnecting'

export interface LiveGalleryState {
  event: PublicEvent | null
  /** Shown in the grid, newest first. */
  photos: PublicPhoto[]
  /** Arrived while the guest was scrolled down / viewing a photo; revealed on tap. */
  incoming: PublicPhoto[]
  nextCursor: string | null
  status: LoadStatus
  connection: Connection
  loadingMore: boolean
}

type Action =
  | { type: 'loaded'; event: PublicEvent; page: PublicPhotoPage }
  | { type: 'failed'; status: LoadStatus }
  | { type: 'arrived'; photos: PublicPhoto[]; show: boolean }
  | { type: 'showIncoming' }
  | { type: 'removed'; id: string }
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
      return { ...state, event: action.event, photos: action.page.photos, nextCursor: action.page.nextCursor, status: 'ready' }
    case 'failed':
      return { ...state, status: action.status }
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
  return { id: m.photoId, thumbnailUrl: m.thumbnailUrl, webUrl: m.webUrl, width: m.width, height: m.height, readyAt: m.readyAt, cursor: m.cursor }
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
export function useLiveGallery(slug: string, shouldShowImmediately: () => boolean) {
  const [state, dispatch] = useReducer(reducer, initialState)
  const newest = useRef<PublicPhoto | null>(null)
  const showRef = useRef(shouldShowImmediately)
  useEffect(() => {
    showRef.current = shouldShowImmediately
  })

  const trackNewest = useCallback((photos: PublicPhoto[]) => {
    for (const p of photos) {
      if (!newest.current || byNewest(p, newest.current) < 0) newest.current = p
    }
  }, [])

  const arrive = useCallback(
    (photos: PublicPhoto[]) => {
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
    Promise.all([publicApi.event(slug), publicApi.photos(slug, { limit: 40 })])
      .then(([event, page]) => {
        if (cancelled) return
        trackNewest(page.photos)
        dispatch({ type: 'loaded', event, page })
      })
      .catch((e) => {
        if (!cancelled) dispatch({ type: 'failed', status: e instanceof ApiError && e.status === 404 ? 'not-found' : 'error' })
      })
    return () => {
      cancelled = true
    }
  }, [slug, trackNewest])

  const catchUp = useCallback(async () => {
    try {
      const page = newest.current
        ? await publicApi.photos(slug, { after: newest.current.cursor, limit: 200 })
        : await publicApi.photos(slug, { limit: 40 })
      arrive(page.photos)
    } catch {
      // next reconnect / poll will try again
    }
  }, [slug, arrive])

  // Live stream
  const loaded = state.status === 'ready'
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

    const poll = setInterval(() => void catchUp(), SAFETY_POLL_MS)
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
      dispatch({ type: 'moreLoaded', page: await publicApi.photos(slug, { before: state.nextCursor, limit: 40 }) })
    } catch {
      dispatch({ type: 'moreFailed' })
    }
  }, [slug, state.nextCursor, state.loadingMore])

  const showIncoming = useCallback(() => dispatch({ type: 'showIncoming' }), [])

  return { ...state, loadMore, showIncoming }
}
