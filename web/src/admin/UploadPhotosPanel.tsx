import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { Category } from '../lib/types'
import { ACCEPTED_TYPES, preparePhoto } from './prepareUpload'
import { Button, Card } from './ui'

/** Photos prepared and sent at the same time: keeps the browser responsive and the server unhurried. */
const PARALLEL = { compressed: 3, original: 2 }
/** Refresh the dashboard counts and photo list every this many uploads. */
const REFRESH_EVERY = 10

type Status = 'waiting' | 'working' | 'done' | 'duplicate' | 'failed'
/** compressed: gallery versions only. original: the full camera file is kept too (client delivery, albums). */
type Mode = 'compressed' | 'original'

interface Item {
  file: File
  mode: Mode
  /** Upload "into" this category (e.g. the Cake cutting folder). */
  categoryId?: string
  status: Status
  error?: string
}

/** PUTs the full-quality file straight to storage with the signed headers (never through the small server). */
async function putOriginal(file: File, upload: { url: string; method: string; headers: Record<string, string> }) {
  const headers = Object.fromEntries(Object.entries(upload.headers).filter(([k]) => !/^(host|content-length)$/i.test(k)))
  let res: Response
  try {
    res = await fetch(upload.url, { method: upload.method, headers, body: file })
  } catch {
    throw new Error('Could not reach photo storage (check the storage CORS setting)')
  }
  if (!res.ok) throw new Error(`Photo storage refused the original (${res.status})`)
}

/** One photo: compressed → gallery versions; original → full file to storage, then the gallery versions. */
async function uploadOne(eventId: string, item: Item): Promise<'done' | 'duplicate'> {
  const prepared = await preparePhoto(item.file)
  if (item.mode === 'compressed') {
    const res = await adminApi.uploadPhoto(eventId, item.file, prepared, false, item.categoryId)
    return res.outcome === 'DUPLICATE' ? 'duplicate' : 'done'
  }
  const session = await adminApi.originalUpload(eventId, item.file, prepared.checksumSha256, item.categoryId)
  if (session.outcome === 'DUPLICATE' || !session.upload) return 'duplicate'
  await putOriginal(item.file, session.upload)
  if (session.needsVersions) await adminApi.uploadPhoto(eventId, item.file, prepared, true, item.categoryId)
  else await adminApi.attachOriginal(eventId, session.photoId)
  return 'done'
}

const MODES: Record<Mode, { title: string; badge: string; use: string; detail: string }> = {
  compressed: {
    title: 'Upload compressed photos',
    badge: 'Fast',
    use: 'For the guest gallery: live events, quick sharing, extra shots.',
    detail: 'Sends a 2048 px copy (about 1 MB per photo), so even 2,000 photos upload in minutes. Guests view and download this quality: perfect for phones and WhatsApp.',
  },
  original: {
    title: 'Upload original photos',
    badge: 'Full quality',
    use: 'For client delivery: edited finals, album selection, print-quality downloads.',
    detail: 'Also keeps the full camera file (about 8–15 MB per photo, so slower). Photos already in the gallery just get their original added, never duplicated.',
  },
}

function reason(err: unknown) {
  if (err instanceof ApiError) {
    if (err.status === 413) return 'Too large for the server upload limit'
    return err.message
  }
  return err instanceof Error ? err.message : 'Upload failed'
}

/**
 * Manual upload: the admin adds photos from their computer (edited photos after the event, extra shots).
 * Each photo is compressed in the browser and appears in the guest gallery at once, behind the same QR.
 */
export function UploadPhotosPanel({ eventId, onUploaded, categories = [] }: { eventId: string; onUploaded: () => void; categories?: Category[] }) {
  const [items, setItems] = useState<Item[]>([])
  const [categoryId, setCategoryId] = useState('')
  const queue = useRef<Item[]>([])
  const active = useRef(0)
  const sinceRefresh = useRef(0)
  const stopped = useRef(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)
  const pickMode = useRef<Mode>('compressed')
  const [dragging, setDraggingMode] = useState<Mode | null>(null)

  const running = items.some((i) => i.status === 'waiting' || i.status === 'working')
  const count = (s: Status) => items.filter((i) => i.status === s).length
  const finished = count('done') + count('duplicate') + count('failed')

  // Closing the tab mid-upload loses the photos not sent yet: ask first.
  useEffect(() => {
    if (!running) return
    const warn = (e: BeforeUnloadEvent) => e.preventDefault()
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [running])

  useEffect(() => () => {
    stopped.current = true
  }, [])

  const set = (item: Item, status: Status, error?: string) => {
    item.status = status
    item.error = error
    setItems((list) => [...list])
  }

  const pump = () => {
    while (!stopped.current && queue.current.length > 0 && active.current < PARALLEL[queue.current[0].mode]) {
      const item = queue.current.shift()!
      active.current++
      set(item, 'working')
      void (async () => {
        try {
          const outcome = await uploadOne(eventId, item)
          set(item, outcome)
          if (outcome === 'done' && ++sinceRefresh.current >= REFRESH_EVERY) {
            sinceRefresh.current = 0
            onUploaded()
          }
        } catch (err) {
          set(item, 'failed', reason(err))
        } finally {
          active.current--
          if (active.current === 0 && queue.current.length === 0) {
            sinceRefresh.current = 0
            onUploaded()
          }
          pump()
        }
      })()
    }
  }

  const add = (files: FileList | File[] | null, mode: Mode) => {
    const photos = Array.from(files ?? []).filter((f) => ACCEPTED_TYPES.includes(f.type))
    if (photos.length === 0) return
    stopped.current = false
    const fresh = photos
      .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
      .map((file): Item => ({ file, mode, categoryId: categoryId || undefined, status: 'waiting' }))
    // A new batch starts a new summary once the previous one is complete.
    setItems((list) => (list.some((i) => i.status === 'waiting' || i.status === 'working') ? [...list, ...fresh] : fresh))
    queue.current.push(...fresh)
    pump()
  }

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    add(e.target.files, pickMode.current)
    e.target.value = '' // the same files can be picked again
  }

  const pick = (mode: Mode, input: HTMLInputElement | null) => {
    pickMode.current = mode
    input?.click()
  }

  const onDrop = (e: DragEvent, mode: Mode) => {
    e.preventDefault()
    setDraggingMode(null)
    add(e.dataTransfer.files, mode)
  }

  const retryFailed = () => {
    const failed = items.filter((i) => i.status === 'failed')
    failed.forEach((i) => set(i, 'waiting'))
    stopped.current = false
    queue.current.push(...failed)
    pump()
  }

  const stop = () => {
    queue.current.splice(0).forEach((i) => set(i, 'failed', 'Stopped'))
  }

  const failures = items.filter((i) => i.status === 'failed')
  return (
    <Card title="Upload photos">
      {categories.length > 0 && (
        <label className="mb-3 flex flex-wrap items-center gap-2 text-sm">
          <span className="text-muted">Upload into category</span>
          <select aria-label="Upload into category" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}
            className="h-9 rounded-lg border border-line bg-white px-2.5 text-sm outline-none focus:border-ink">
            <option value="">Not sorted (sort later)</option>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <span className="text-xs text-muted">e.g. pick “Cake cutting”, then choose that folder</span>
        </label>
      )}
      <div className="grid gap-3 sm:grid-cols-2">
        {(['compressed', 'original'] as const).map((mode) => (
          <div
            key={mode}
            onDragOver={(e) => {
              e.preventDefault()
              setDraggingMode(mode)
            }}
            onDragLeave={() => setDraggingMode(null)}
            onDrop={(e) => onDrop(e, mode)}
            className={`flex flex-col rounded-xl border-2 border-dashed p-4 transition-colors ${dragging === mode ? 'border-ink bg-stone-50' : 'border-line'}`}
            data-testid={`upload-${mode}`}
          >
            <div className="flex items-center justify-between gap-2">
              <p className="font-medium">{MODES[mode].title}</p>
              <span className="shrink-0 rounded-full bg-stone-100 px-2 py-0.5 text-xs font-medium text-muted">{MODES[mode].badge}</span>
            </div>
            <p className="mt-2 text-sm text-ink">{MODES[mode].use}</p>
            <p className="mt-1 text-xs text-muted">{MODES[mode].detail}</p>
            <div className="mt-auto flex flex-wrap gap-2 pt-4">
              <Button variant={mode === 'compressed' ? 'primary' : 'secondary'} onClick={() => pick(mode, fileInput.current)}>Choose photos</Button>
              <Button variant="ghost" onClick={() => pick(mode, folderInput.current)}>Choose a folder</Button>
            </div>
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs text-muted">JPEG or PNG · or drag photos onto either box</p>
      <input ref={fileInput} type="file" multiple accept={ACCEPTED_TYPES.join(',')} className="hidden" onChange={onPick} aria-label="Photos to upload" />
      <input
        ref={folderInput}
        type="file"
        multiple
        className="hidden"
        onChange={onPick}
        aria-label="Folder to upload"
        {...({ webkitdirectory: '' } as Record<string, string>)}
      />

      {items.length > 0 && (
        <div className="mt-4 space-y-2" data-testid="upload-progress">
          <div className="flex items-baseline justify-between text-sm">
            <span className="font-medium tabular-nums">
              {formatCount(finished)} of {formatCount(items.length)} {running ? 'processed' : 'done'}
            </span>
            {running && <span className="text-xs text-muted">Keep this page open until it finishes</span>}
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-stone-100">
            <div className="h-full rounded-full bg-ink transition-[width]" style={{ width: `${(finished / items.length) * 100}%` }} />
          </div>
          <p className="text-sm text-muted tabular-nums">
            <b className="text-ink">{formatCount(count('done'))}</b> added to the gallery
            {count('duplicate') > 0 && <> · {formatCount(count('duplicate'))} already there (skipped)</>}
            {failures.length > 0 && <> · <span className="text-red-700">{formatCount(failures.length)} failed</span></>}
          </p>
          {failures.length > 0 && !running && (
            <div className="rounded-lg bg-red-50 p-3 text-sm text-red-800">
              <ul className="max-h-28 space-y-0.5 overflow-auto">
                {failures.slice(0, 50).map((i, n) => (
                  <li key={n}><span className="font-medium">{i.file.name}</span>: {i.error}</li>
                ))}
              </ul>
              <div className="mt-2"><Button onClick={retryFailed}>Retry failed</Button></div>
            </div>
          )}
          {running && <Button variant="ghost" onClick={stop}>Stop</Button>}
        </div>
      )}
    </Card>
  )
}
