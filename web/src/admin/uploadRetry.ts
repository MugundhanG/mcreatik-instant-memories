import { ApiError } from '../lib/api'

/** Waits before the 2nd and 3rd try of a photo that failed for a passing reason (exported so tests can skip them). */
export const RETRY_DELAYS_MS = [1_500, 5_000]

/** A failure that may well work on the next try: the connection dropped, storage or the server hiccuped. */
export class TransientError extends Error {}

function isTransient(err: unknown) {
  if (err instanceof TransientError || err instanceof TypeError) return true
  return err instanceof ApiError && (err.status >= 500 || err.status === 408 || err.status === 429)
}

/**
 * Tries a photo again (the server treats every step as repeatable: the same photo is never added twice) before
 * reporting it as failed. A refused login (401) or a real answer such as "storage full" is never retried here.
 */
export async function withRetry<T>(run: () => Promise<T>, delays = RETRY_DELAYS_MS): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try {
      return await run()
    } catch (err) {
      if (attempt >= delays.length || !isTransient(err)) throw err
      await new Promise((resolve) => setTimeout(resolve, delays[attempt]))
    }
  }
}
