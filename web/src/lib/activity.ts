import { API_BASE_URL } from './api'

/**
 * Anonymous guest activity for the studio's analytics: what was opened, viewed, downloaded, followed.
 * The only identifier is a random id this browser makes up (to count distinct people); nothing personal.
 * Items are batched and sent every few seconds so a full venue on one Wi-Fi stays light on the server.
 */
export type ActivityType = 'VISIT' | 'PHOTO_VIEW' | 'DOWNLOAD' | 'SHARE' | 'INSTAGRAM' | 'CONTACT' | 'LIKE' | 'UNLIKE'

interface Item {
  type: ActivityType
  photoId?: string
  source?: 'qr' | 'link' | 'face'
}

const FLUSH_MS = 4_000
const MAX_BATCH = 50
const VISITOR_KEY = 'mcreatik.visitor'

let visitor: string | null = null
const queues = new Map<string, Item[]>()
let timer: ReturnType<typeof setTimeout> | null = null

export function visitorId() {
  if (visitor) return visitor
  try {
    visitor = localStorage.getItem(VISITOR_KEY)
    if (!visitor) {
      visitor = crypto.randomUUID()
      localStorage.setItem(VISITOR_KEY, visitor)
    }
  } catch {
    visitor ??= crypto.randomUUID() // storage blocked (private mode): one id for this page load
  }
  return visitor
}

/** Most items kept per gallery while the network is down; the oldest go first. */
const MAX_QUEUE = 500
const MAX_BACKOFF_MS = 60_000
let failures = 0

const activityUrl = (slug: string) => `${API_BASE_URL}/api/public/events/${encodeURIComponent(slug)}/activity`

function schedule(ms: number) {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => flushActivity(), ms)
}

/** A batch that did not get through goes back in front of the queue and is retried with a growing delay. */
function requeue(slug: string, items: Item[]) {
  queues.set(slug, [...items, ...(queues.get(slug) ?? [])].slice(-MAX_QUEUE))
  failures++
  schedule(Math.min(FLUSH_MS * 2 ** failures, MAX_BACKOFF_MS))
}

function send(slug: string, items: Item[]) {
  // keepalive lets the last batch go out while the page closes.
  fetch(activityUrl(slug), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId(), items }),
    keepalive: true,
  })
    .then((res) => {
      if (res.ok || (res.status >= 400 && res.status < 500 && res.status !== 429)) failures = 0 // 4xx (gallery closed...): never going to work, drop
      else requeue(slug, items)
    })
    .catch(() => requeue(slug, items)) // analytics must never disturb the gallery, but a flaky venue network must not lose them
}

/** {@code beacon}: the page is going away, so hand the batch to the browser, which delivers it even after unload. */
export function flushActivity(beacon = false) {
  if (timer) clearTimeout(timer)
  timer = null
  for (const [slug, items] of [...queues]) {
    queues.delete(slug) // taken out before sending: a batch is never sent twice
    while (items.length > 0) {
      const batch = items.splice(0, MAX_BATCH)
      const body = JSON.stringify({ visitorId: visitorId(), items: batch })
      let queued = false
      if (beacon && typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
        try {
          queued = navigator.sendBeacon(activityUrl(slug), new Blob([body], { type: 'application/json' }))
        } catch {
          queued = false
        }
      }
      if (!queued) send(slug, batch)
    }
  }
}

export function track(slug: string, item: Item) {
  const items = queues.get(slug) ?? []
  items.push(item)
  queues.set(slug, items)
  if (queues.get(slug)!.length > MAX_QUEUE) queues.set(slug, queues.get(slug)!.slice(-MAX_QUEUE))
  if (items.length >= MAX_BATCH && failures === 0) flushActivity()
  else timer ??= setTimeout(() => flushActivity(), FLUSH_MS)
}

/** Counts one gallery visit per browser tab, however often the page reloads. */
export function trackVisit(slug: string, source: 'qr' | 'link') {
  const key = `mcreatik.visit.${slug}`
  try {
    if (sessionStorage.getItem(key)) return
    sessionStorage.setItem(key, '1')
  } catch {
    // storage blocked: count it anyway
  }
  track(slug, { type: 'VISIT', source })
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => flushActivity(true))
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushActivity(true)
  })
}
