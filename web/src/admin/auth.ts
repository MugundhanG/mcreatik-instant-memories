import { useSyncExternalStore } from 'react'
import { adminApi, configureAuth } from '../lib/api'
import type { AdminRole, LoginResponse } from '../lib/types'

const KEY = 'mcreatik.admin.session'

export interface Session {
  token: string
  expiresAt: string
  /** When this token was issued (ms): the session is refreshed once half of its life has passed. */
  issuedAt?: number
  email: string
  displayName: string
  /** Missing in sessions saved before studio accounts; refreshed from the server on load. */
  role?: AdminRole
  mustChangePassword?: boolean
}

const listeners = new Set<() => void>()
let current: Session | null = read()

function read(): Session | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const session = JSON.parse(raw) as Session
    return Date.parse(session.expiresAt) > Date.now() ? session : null
  } catch {
    return null
  }
}

function set(session: Session | null) {
  current = session
  if (session) startKeeper()
  else stopKeeper()
  try {
    if (session) localStorage.setItem(KEY, JSON.stringify(session))
    else localStorage.removeItem(KEY)
  } catch {
    // storage unavailable (private mode): session lives in memory only
  }
  listeners.forEach((l) => l())
}

export const auth = {
  signIn: (r: LoginResponse) =>
    set({ token: r.token, expiresAt: r.expiresAt, issuedAt: Date.now(), email: r.email, displayName: r.displayName, role: r.role, mustChangePassword: r.mustChangePassword }),
  /** Account details changed (refreshed from the server, password changed). */
  update: (patch: Partial<Session>) => current && set({ ...current, ...patch }),
  signOut: () => set(null),
  session: () => current,
}

/** Before this share of a token's life has passed it is left alone; after it, the next chance swaps it for a new one. */
const REFRESH_AT = 0.5
/** A 12-hour token when the session was saved before issue times were kept. */
const DEFAULT_LIFETIME_MS = 12 * 3_600_000
const CHECK_EVERY_MS = 60_000

export function sessionNeedsRefresh(session: Session, now = Date.now()): boolean {
  const expires = Date.parse(session.expiresAt)
  const issued = session.issuedAt ?? expires - DEFAULT_LIFETIME_MS
  return now >= issued + (expires - issued) * REFRESH_AT
}

let refreshing: Promise<void> | null = null

/**
 * Sliding session: a studio that keeps working (a long upload) always has a token with plenty of life left, so it
 * is never logged out mid-task. A failed refresh (network, server restart) is ignored and retried at the next check;
 * only the server saying the token itself is no longer valid (401) logs out.
 */
export function refreshSessionIfNeeded(): Promise<void> {
  const session = current
  if (!session || !sessionNeedsRefresh(session)) return Promise.resolve()
  refreshing ??= (async () => {
    try {
      const fresh = await adminApi.refreshSession()
      // Only if the same session is still the current one (not signed out, not replaced meanwhile).
      if (current?.token === session.token) {
        set({ ...current, token: fresh.token, expiresAt: fresh.expiresAt, issuedAt: Date.now(), role: fresh.role ?? current.role,
          mustChangePassword: fresh.mustChangePassword ?? current.mustChangePassword })
      }
    } catch {
      // try again at the next request or check
    } finally {
      refreshing = null
    }
  })()
  return refreshing
}

let keeper: ReturnType<typeof setInterval> | null = null
const onVisible = () => {
  if (document.visibilityState === 'visible') void refreshSessionIfNeeded()
}
function startKeeper() {
  if (keeper || typeof window === 'undefined') return
  keeper = setInterval(() => void refreshSessionIfNeeded(), CHECK_EVERY_MS)
  document.addEventListener('visibilitychange', onVisible)
}
function stopKeeper() {
  if (keeper) clearInterval(keeper)
  keeper = null
  if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', onVisible)
}
if (current) startKeeper()

configureAuth(
  () => current?.token ?? null,
  // Only the token the server just rejected signs out: a request sent before a refresh must not end the new session.
  (rejected) => {
    if (rejected === null || current?.token === rejected) set(null)
  },
  refreshSessionIfNeeded,
)

export function useSession(): Session | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l)
      return () => listeners.delete(l)
    },
    () => current,
  )
}
