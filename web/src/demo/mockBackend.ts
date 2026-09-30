/**
 * In-browser stand-in for the McreatiK backend, used only by the demo build (VITE_DEMO=1).
 * It answers the app's real API calls (fetch) and its live stream (EventSource), so the demo runs
 * the production UI code unchanged. Nothing here is included in the production bundle.
 */
import { API_BASE_URL } from '../lib/api'
import type { AdminPhoto, EventStatus, PublicPhoto, UploaderStatus } from '../lib/types'
import { generatePhoto } from './photos'

const GALLERY_BASE = 'https://gallery.mcreatik.com/e/'
const ARRIVAL_MS = 7_000

interface DemoUploader {
  id: string
  name: string
  lastSeenAt: number
  offlineUntil: number
  queued: number
  tokenPrefix: string
}

interface DemoPhoto {
  id: string
  uploaderId: string | null
  originalFileName: string
  fileSize: number
  thumbnailUrl: string
  webUrl: string
  width: number
  height: number
  readyAt: string
  createdAt: string
}

interface DemoEvent {
  id: string
  name: string
  slug: string
  eventDate: string
  status: EventStatus
  coverPhotoId: string | null
  retentionUntil: string | null
  createdAt: string
  photos: DemoPhoto[] // newest first
  uploaders: DemoUploader[]
  nextFrame: number
}

let idCounter = 1
const uid = (prefix: string) => `${prefix}-${(idCounter++).toString(16).padStart(8, '0')}-demo`
let seedCounter = 11
let clock = Date.now()
const nowIso = () => {
  clock = Math.max(clock + 1, Date.now())
  return new Date(clock).toISOString()
}
const today = () => new Date().toISOString().slice(0, 10)
const plusDays = (d: string, n: number) => new Date(Date.parse(d) + n * 86_400_000).toISOString().slice(0, 10)

const events: DemoEvent[] = []

function makeUploaders(names: string[]): DemoUploader[] {
  return names.map((name) => ({ id: uid('upl'), name, lastSeenAt: Date.now() - 3_000, offlineUntil: 0, queued: 0, tokenPrefix: 'mku_' + Math.random().toString(36).slice(2, 8) }))
}

function createPhoto(event: DemoEvent, uploader: DemoUploader | null, readyAt: string): DemoPhoto {
  const frame = event.nextFrame++
  const camera = uploader?.name ?? 'Camera'
  const img = generatePhoto(seedCounter++, `Demo photo · ${camera} · #${frame}`)
  return {
    id: uid('photo'),
    uploaderId: uploader?.id ?? null,
    originalFileName: `IMG_${String(4000 + frame).padStart(4, '0')}.JPG`,
    fileSize: 8_000_000 + Math.round(Math.random() * 6_000_000),
    ...img,
    readyAt,
    createdAt: readyAt,
  }
}

function addEvent(name: string, slug: string, status: EventStatus, eventDate: string, cameras: string[], photoCount: number) {
  const event: DemoEvent = {
    id: uid('evt'), name, slug, eventDate, status, coverPhotoId: null, retentionUntil: plusDays(eventDate, 90),
    createdAt: new Date(Date.now() - 86_400_000).toISOString(), photos: [], uploaders: makeUploaders(cameras), nextFrame: 1,
  }
  for (let i = photoCount; i > 0; i--) {
    const up = event.uploaders[i % event.uploaders.length] ?? null
    event.photos.unshift(createPhoto(event, up, new Date(Date.now() - i * 4 * 60_000).toISOString()))
  }
  events.push(event)
  return event
}

function seed() {
  addEvent('Arun & Priya Wedding', 'arun-priya-7k3d', 'LIVE', today(), ['Camera 1', 'Camera 2', 'Camera 3'], 18)
  const bday = addEvent('Kavya’s 30th Birthday', 'kavya-30-m2qa', 'COMPLETED', plusDays(today(), -9), ['Camera 1'], 6)
  bday.uploaders.forEach((u) => (u.lastSeenAt = Date.now() - 9 * 86_400_000))
  addEvent('Nexa Corporate Summit', 'nexa-summit-h7tr', 'DRAFT', plusDays(today(), 12), [], 0)
}

// ---------------- live stream ----------------

type Listener = (e: MessageEvent) => void

class DemoEventSource {
  static readonly CONNECTING = 0
  static readonly OPEN = 1
  static readonly CLOSED = 2
  readonly url: string
  readyState = DemoEventSource.CONNECTING
  onerror: (() => void) | null = null
  private listeners: Record<string, Listener[]> = {}
  readonly slug: string

  constructor(url: string) {
    this.url = url
    this.slug = decodeURIComponent(url.split('/events/')[1]?.split('/')[0] ?? '')
    subscribers.add(this)
    setTimeout(() => this.emit('CONNECTED', {}), 150)
  }

  addEventListener(type: string, fn: Listener) {
    ;(this.listeners[type] ??= []).push(fn)
  }

  removeEventListener(type: string, fn: Listener) {
    this.listeners[type] = (this.listeners[type] ?? []).filter((l) => l !== fn)
  }

  emit(type: string, data: unknown) {
    if (this.readyState === DemoEventSource.CLOSED) return
    this.readyState = DemoEventSource.OPEN
    for (const fn of this.listeners[type] ?? []) fn(new MessageEvent(type, { data: JSON.stringify(data) }))
  }

  close() {
    this.readyState = DemoEventSource.CLOSED
    subscribers.delete(this)
  }
}

const subscribers = new Set<DemoEventSource>()

function broadcast(event: DemoEvent, type: string, data: unknown) {
  for (const s of subscribers) if (s.slug === event.slug) s.emit(type, data)
}

function toPublic(p: DemoPhoto): PublicPhoto {
  return { id: p.id, thumbnailUrl: p.thumbnailUrl, webUrl: p.webUrl, width: p.width, height: p.height, readyAt: p.readyAt, cursor: p.id }
}

function toAdmin(p: DemoPhoto): AdminPhoto {
  return {
    id: p.id, uploaderId: p.uploaderId, originalFileName: p.originalFileName, status: 'READY', failureReason: null,
    fileSize: p.fileSize, width: p.width, height: p.height, thumbnailUrl: p.thumbnailUrl, webUrl: p.webUrl,
    capturedAt: p.readyAt, uploadedAt: p.readyAt, readyAt: p.readyAt, createdAt: p.createdAt,
  }
}

function publish(event: DemoEvent, uploader: DemoUploader | null) {
  const photo = createPhoto(event, uploader, nowIso())
  event.photos.unshift(photo)
  if (event.status === 'UPCOMING') {
    event.status = 'LIVE'
    broadcast(event, 'EVENT_STATUS', { eventId: event.id, status: 'LIVE', live: true })
  }
  if (uploader) uploader.lastSeenAt = Date.now()
  broadcast(event, 'PHOTO_READY', { eventId: event.id, photoId: photo.id, ...toPublic(photo) })
  notify()
}

// ---------------- simulation ----------------

let auto = true
const changeListeners = new Set<() => void>()
const notify = () => changeListeners.forEach((l) => l())

function tick() {
  const now = Date.now()
  for (const event of events) {
    if (event.status !== 'LIVE' || event.uploaders.length === 0) continue
    for (const u of event.uploaders) {
      if (u.offlineUntil > now) continue
      if (u.offlineUntil && u.offlineUntil <= now) {
        // Back online: the queued photos arrive one after another, nothing lost.
        u.offlineUntil = 0
        const backlog = u.queued
        u.queued = 0
        for (let i = 0; i < backlog; i++) setTimeout(() => publish(event, u), 400 * i)
      }
      u.lastSeenAt = now
    }
  }
}

function arrive() {
  if (!auto) return
  const event = events.find((e) => e.slug === 'arun-priya-7k3d')
  if (!event || event.status !== 'LIVE') return
  const u = event.uploaders[Math.floor(Math.random() * event.uploaders.length)]
  if (u.offlineUntil > Date.now()) {
    u.queued++ // shooting continues while offline: photos wait on the laptop
    return
  }
  publish(event, u)
}

function outageCycle() {
  const event = events.find((e) => e.slug === 'arun-priya-7k3d')
  const cam3 = event?.uploaders.find((u) => u.name === 'Camera 3')
  if (cam3 && auto) cam3.offlineUntil = Date.now() + 30_000
}

export const demo = {
  /** Adds a photo to the gallery with this slug right now. */
  addPhoto(slug: string) {
    const event = events.find((e) => e.slug === slug)
    if (!event || event.status === 'ARCHIVED' || event.status === 'DRAFT') return false
    const online = event.uploaders.filter((u) => u.offlineUntil <= Date.now())
    publish(event, online[Math.floor(Math.random() * online.length)] ?? null)
    return true
  },
  isAuto: () => auto,
  setAuto(on: boolean) {
    auto = on
    notify()
  },
  subscribe(listener: () => void) {
    changeListeners.add(listener)
    return () => {
      changeListeners.delete(listener)
    }
  },
  mainSlug: 'arun-priya-7k3d',
}

// ---------------- API ----------------

const json = (body: unknown, status = 200) =>
  new Response(status === 204 ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const notFound = () => json({ code: 'NOT_FOUND', message: 'Not found' }, 404)

function effectiveStatus(u: DemoUploader): UploaderStatus {
  return Date.now() - u.lastSeenAt < 45_000 && u.offlineUntil <= Date.now() ? 'ONLINE' : 'OFFLINE'
}

function detail(e: DemoEvent) {
  const cover = e.photos.find((p) => p.id === e.coverPhotoId)
  return {
    id: e.id, name: e.name, slug: e.slug, eventDate: e.eventDate, startTime: null, endTime: null, status: e.status,
    coverPhotoId: e.coverPhotoId, coverUrl: cover?.webUrl ?? null, galleryUrl: GALLERY_BASE + e.slug,
    retentionUntil: e.retentionUntil, createdAt: e.createdAt, updatedAt: e.createdAt,
  }
}

function stats(e: DemoEvent) {
  const uploaders = e.uploaders.map((u) => {
    const mine = e.photos.filter((p) => p.uploaderId === u.id)
    return {
      id: u.id, name: u.name, status: effectiveStatus(u), lastSeenAt: new Date(u.lastSeenAt).toISOString(),
      deviceIdentifier: `${u.name.toLowerCase().replace(/\s/g, '-')}-laptop`, tokenPrefix: u.tokenPrefix,
      photosUploaded: mine.length, photosReady: mine.length, queuePending: u.queued, queueFailed: 0, lastError: null,
      lastUploadAt: mine[0]?.readyAt ?? null,
    }
  })
  return {
    totalPhotos: e.photos.length, readyPhotos: e.photos.length, inProgressPhotos: 0, failedPhotos: 0,
    storageBytes: e.photos.reduce((s, p) => s + p.fileSize, 0), activeUploaders: uploaders.filter((u) => u.status !== 'OFFLINE').length,
    latestPhoto: e.photos[0] ? toAdmin(e.photos[0]) : null, uploaders,
  }
}

function page<T>(items: T[], index: number, limit: number) {
  const slice = items.slice(index, index + limit)
  return { slice, next: index + limit < items.length }
}

function credentials(u: DemoUploader) {
  const token = 'mku_demo_' + Math.random().toString(36).slice(2, 14)
  u.tokenPrefix = token.slice(0, 10)
  const code = 'MCK1.' + btoa(JSON.stringify({ server: 'https://api.mcreatik.com', token })).replace(/=+$/, '')
  return { uploaderId: u.id, name: u.name, token, connectionCode: code }
}

async function handle(method: string, url: URL, body: unknown): Promise<Response> {
  const path = url.pathname
  const q = url.searchParams
  let m: RegExpMatchArray | null

  if (method === 'POST' && path === '/api/auth/login') {
    const email = (body as { email?: string })?.email || 'demo@mcreatik.com'
    return json({ token: 'demo', expiresAt: new Date(Date.now() + 12 * 3_600_000).toISOString(), email, displayName: 'Demo' })
  }

  // ---- public ----
  if ((m = path.match(/^\/api\/public\/events\/([^/]+)(\/photos)?$/))) {
    const e = events.find((x) => x.slug === decodeURIComponent(m![1]))
    if (!e || e.status === 'DRAFT' || e.status === 'ARCHIVED') return notFound()
    if (!m[2]) {
      const cover = e.photos.find((p) => p.id === e.coverPhotoId)
      return json({ name: e.name, slug: e.slug, eventDate: e.eventDate, status: e.status, live: e.status === 'LIVE', coverUrl: cover?.webUrl ?? null, photoCount: e.photos.length })
    }
    const limit = Number(q.get('limit') ?? 40)
    if (q.get('after')) {
      const idx = e.photos.findIndex((p) => p.id === q.get('after'))
      const newer = (idx < 0 ? [] : e.photos.slice(0, idx)).reverse()
      return json({ photos: newer.map(toPublic), nextCursor: null })
    }
    const start = q.get('before') ? e.photos.findIndex((p) => p.id === q.get('before')) + 1 : 0
    const { slice, next } = page(e.photos, start, limit)
    return json({ photos: slice.map(toPublic), nextCursor: next ? slice[slice.length - 1].id : null })
  }

  // ---- admin ----
  if (path === '/api/admin/events' && method === 'GET') {
    return json(events.map((e) => ({ id: e.id, name: e.name, slug: e.slug, eventDate: e.eventDate, status: e.status, galleryUrl: GALLERY_BASE + e.slug, readyPhotos: e.photos.length, createdAt: e.createdAt })))
  }
  if (path === '/api/admin/events' && method === 'POST') {
    const b = body as { name: string; eventDate: string; status?: EventStatus; slug?: string }
    const base = (b.slug || b.name).toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')
    const slug = b.slug ? base : `${base || 'event'}-${Math.random().toString(36).slice(2, 6)}`
    if (events.some((e) => e.slug === slug)) return json({ code: 'SLUG_TAKEN', message: 'This gallery address is already in use' }, 409)
    const e = addEvent(b.name, slug, b.status ?? 'UPCOMING', b.eventDate, [], 0)
    return json(detail(e), 201)
  }
  if ((m = path.match(/^\/api\/admin\/events\/([^/]+)(\/stats|\/photos|\/uploaders)?$/))) {
    const e = events.find((x) => x.id === m![1])
    if (!e) return notFound()
    const sub = m[2]
    if (!sub && method === 'GET') return json(detail(e))
    if (!sub && method === 'PATCH') {
      const b = body as Partial<{ name: string; status: EventStatus; coverPhotoId: string; retentionUntil: string; clearRetention: boolean }>
      if (b.name) e.name = b.name
      if (b.coverPhotoId) e.coverPhotoId = b.coverPhotoId
      if (b.clearRetention) e.retentionUntil = null
      else if (b.retentionUntil) e.retentionUntil = b.retentionUntil
      if (b.status && b.status !== e.status) {
        e.status = b.status
        broadcast(e, 'EVENT_STATUS', { eventId: e.id, status: e.status, live: e.status === 'LIVE' })
      }
      notify()
      return json(detail(e))
    }
    if (!sub && method === 'DELETE') {
      events.splice(events.indexOf(e), 1)
      return json(null, 204)
    }
    if (sub === '/stats') return json(stats(e))
    if (sub === '/photos') {
      const start = q.get('before') ? e.photos.findIndex((p) => p.id === q.get('before')) + 1 : 0
      const { slice, next } = page(e.photos, start, Number(q.get('limit') ?? 60))
      return json({ photos: slice.map(toAdmin), nextCursor: next ? slice[slice.length - 1].id : null })
    }
    if (sub === '/uploaders' && method === 'POST') {
      const u = makeUploaders([(body as { name: string }).name])[0]
      u.lastSeenAt = 0
      e.uploaders.push(u)
      return json(credentials(u), 201)
    }
  }
  if ((m = path.match(/^\/api\/admin\/uploaders\/([^/]+)(\/rotate-token)?$/))) {
    const e = events.find((x) => x.uploaders.some((u) => u.id === m![1]))
    const u = e?.uploaders.find((x) => x.id === m![1])
    if (!e || !u) return notFound()
    if (m[2]) return json(credentials(u))
    if (method === 'DELETE') {
      e.uploaders.splice(e.uploaders.indexOf(u), 1)
      e.photos.forEach((p) => p.uploaderId === u.id && (p.uploaderId = null))
      return json(null, 204)
    }
  }
  if ((m = path.match(/^\/api\/admin\/photos\/([^/]+)(\/original)?$/))) {
    const e = events.find((x) => x.photos.some((p) => p.id === m![1]))
    const p = e?.photos.find((x) => x.id === m![1])
    if (!e || !p) return notFound()
    if (m[2]) return json({ url: p.webUrl })
    if (method === 'DELETE') {
      e.photos.splice(e.photos.indexOf(p), 1)
      if (e.coverPhotoId === p.id) e.coverPhotoId = null
      broadcast(e, 'PHOTO_REMOVED', { eventId: e.id, photoId: p.id })
      notify()
      return json(null, 204)
    }
  }
  return notFound()
}

export function installDemoBackend() {
  seed()
  const realFetch = window.fetch.bind(window)
  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const href = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    if (!href.startsWith(API_BASE_URL)) return realFetch(input, init)
    const method = (init?.method ?? 'GET').toUpperCase()
    const body = typeof init?.body === 'string' && init.body ? JSON.parse(init.body) : undefined
    await new Promise((r) => setTimeout(r, 120)) // feel of a real network
    return handle(method, new URL(href), body)
  }
  ;(window as unknown as { EventSource: unknown }).EventSource = DemoEventSource
  setInterval(tick, 3_000)
  setInterval(arrive, ARRIVAL_MS)
  setTimeout(() => {
    outageCycle()
    setInterval(outageCycle, 110_000)
  }, 40_000)
}
