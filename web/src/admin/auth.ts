import { useSyncExternalStore } from 'react'
import { configureAuth } from '../lib/api'
import type { LoginResponse } from '../lib/types'

const KEY = 'mcreatik.admin.session'

export interface Session {
  token: string
  expiresAt: string
  email: string
  displayName: string
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
  try {
    if (session) localStorage.setItem(KEY, JSON.stringify(session))
    else localStorage.removeItem(KEY)
  } catch {
    // storage unavailable (private mode): session lives in memory only
  }
  listeners.forEach((l) => l())
}

export const auth = {
  signIn: (r: LoginResponse) => set({ token: r.token, expiresAt: r.expiresAt, email: r.email, displayName: r.displayName }),
  signOut: () => set(null),
  session: () => current,
}

configureAuth(
  () => current?.token ?? null,
  () => set(null),
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
