import { API_BASE_URL } from './api'

/**
 * Anonymous guest activity for the studio's analytics: what was opened, viewed, downloaded, followed.
 * The only identifier is a random id this browser makes up (to count distinct people); nothing personal.
 * Items are batched and sent every few seconds so a full venue on one Wi-Fi stays light on the server.
 */
export type ActivityType = 'VISIT' | 'PHOTO_VIEW' | 'DOWNLOAD' | 'SHARE' | 'INSTAGRAM'

interface Item {
  type: ActivityType
  photoId?: string
  source?: 'qr' | 'link'
}

const FLUSH_MS = 4_000
const MAX_BATCH = 50
const VISITOR_KEY = 'mcreatik.visitor'

let visitor: string | null = null
const queues = new Map<string, Item[]>()
let timer: ReturnType<typeof setTimeout> | null = null

function visitorId() {
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

function send(slug: string, items: Item[]) {
  // keepalive lets the last batch go out while the page closes.
  fetch(`${API_BASE_URL}/api/public/events/${encodeURIComponent(slug)}/activity`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ visitorId: visitorId(), items }),
    keepalive: true,
  }).catch(() => {
    // analytics must never disturb the gallery
  })
}

export function flushActivity() {
  if (timer) clearTimeout(timer)
  timer = null
  for (const [slug, items] of queues) {
    while (items.length > 0) send(slug, items.splice(0, MAX_BATCH))
  }
  queues.clear()
}

export function track(slug: string, item: Item) {
  const items = queues.get(slug) ?? []
  items.push(item)
  queues.set(slug, items)
  if (items.length >= MAX_BATCH) flushActivity()
  else timer ??= setTimeout(flushActivity, FLUSH_MS)
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
  window.addEventListener('pagehide', flushActivity)
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') flushActivity()
  })
}
