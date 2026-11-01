import { useCallback, useEffect, useRef, useState } from 'react'
import { API_BASE_URL, ApiError, openZip } from '../lib/api'
import type { PreparedZip, ZipProgress } from '../lib/types'

const POLL_MS = 1000
/** How long "Done" stays on screen. */
const DONE_HIDE_MS = 8000

/** What the progress card shows: preparing, the server's progress, or why it stopped. */
export interface ZipDownloadStatus {
  fileName: string | null
  state: 'PREPARING' | ZipProgress['state']
  done: number
  total: number
  percent: number
  error?: string
}

/**
 * Downloads many photos as one ZIP with a live percentage. The ZIP is built while it streams, so the browser
 * cannot show its size; the server counts the photos written and this polls that once a second.
 */
export function useZipDownload() {
  const [status, setStatus] = useState<ZipDownloadStatus | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const run = useRef(0)

  const stop = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
  }
  useEffect(() => () => {
    run.current++
    stop()
  }, [])

  const dismiss = useCallback(() => {
    run.current++
    stop()
    setStatus(null)
  }, [])

  const start = useCallback(async (prepare: () => Promise<PreparedZip>) => {
    const id = ++run.current
    stop()
    setStatus({ fileName: null, state: 'PREPARING', done: 0, total: 0, percent: 0 })
    let zip: PreparedZip
    try {
      zip = await prepare()
    } catch (err) {
      if (id === run.current) {
        setStatus({ fileName: null, state: 'FAILED', done: 0, total: 0, percent: 0, error: err instanceof ApiError ? err.message : 'Could not start the download. Please try again.' })
      }
      return
    }
    if (id !== run.current) return
    setStatus({ fileName: zip.fileName, state: 'WAITING', done: 0, total: zip.photos, percent: 0 })
    openZip(zip)

    const poll = async () => {
      if (id !== run.current) return
      let p: ZipProgress | null = null
      try {
        const res = await fetch(`${API_BASE_URL}${zip.progressPath}`)
        if (res.ok) p = await res.json()
      } catch {
        // a missed poll: try again next second
      }
      if (id !== run.current) return
      if (p) {
        setStatus({ fileName: zip.fileName, ...p })
        if (p.state === 'DONE') {
          timer.current = setTimeout(() => id === run.current && setStatus(null), DONE_HIDE_MS)
          return
        }
        if (p.state === 'FAILED') return
      }
      timer.current = setTimeout(poll, POLL_MS)
    }
    timer.current = setTimeout(poll, POLL_MS)
  }, [])

  const busy = status !== null && (status.state === 'PREPARING' || status.state === 'WAITING' || status.state === 'QUEUED' || status.state === 'RUNNING')
  return { status, start, dismiss, busy }
}
