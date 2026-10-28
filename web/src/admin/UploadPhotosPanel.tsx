import { useEffect, useRef, useState, type ChangeEvent, type DragEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import { ACCEPTED_TYPES, preparePhoto } from './prepareUpload'
import { Button, Card } from './ui'

/** Photos prepared and sent at the same time: keeps the browser responsive and the server unhurried. */
const PARALLEL = 3
/** Refresh the dashboard counts and photo list every this many uploads. */
const REFRESH_EVERY = 10

type Status = 'waiting' | 'working' | 'done' | 'duplicate' | 'failed'

interface Item {
  file: File
  status: Status
  error?: string
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
export function UploadPhotosPanel({ eventId, onUploaded }: { eventId: string; onUploaded: () => void }) {
  const [items, setItems] = useState<Item[]>([])
  const [dragging, setDragging] = useState(false)
  const queue = useRef<Item[]>([])
  const active = useRef(0)
  const sinceRefresh = useRef(0)
  const stopped = useRef(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const folderInput = useRef<HTMLInputElement>(null)

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
    while (!stopped.current && active.current < PARALLEL && queue.current.length > 0) {
      const item = queue.current.shift()!
      active.current++
      set(item, 'working')
      void (async () => {
        try {
          const prepared = await preparePhoto(item.file)
          const res = await adminApi.uploadPhoto(eventId, item.file, prepared)
          set(item, res.outcome === 'DUPLICATE' ? 'duplicate' : 'done')
          if (res.outcome === 'UPLOAD' && ++sinceRefresh.current >= REFRESH_EVERY) {
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

  const add = (files: FileList | File[] | null) => {
    const photos = Array.from(files ?? []).filter((f) => ACCEPTED_TYPES.includes(f.type))
    if (photos.length === 0) return
    stopped.current = false
    const fresh = photos
      .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
      .map((file): Item => ({ file, status: 'waiting' }))
    // A new batch starts a new summary once the previous one is complete.
    setItems((list) => (list.some((i) => i.status === 'waiting' || i.status === 'working') ? [...list, ...fresh] : fresh))
    queue.current.push(...fresh)
    pump()
  }

  const onPick = (e: ChangeEvent<HTMLInputElement>) => {
    add(e.target.files)
    e.target.value = '' // the same files can be picked again
  }

  const onDrop = (e: DragEvent) => {
    e.preventDefault()
    setDragging(false)
    add(e.dataTransfer.files)
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
      <div
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={`rounded-xl border-2 border-dashed p-5 text-center transition-colors ${dragging ? 'border-ink bg-stone-50' : 'border-line'}`}
        data-testid="upload-drop"
      >
        <p className="text-sm text-muted">
          Add photos from your computer (edited photos after the event, extra shots). They are compressed here and appear in the gallery at once, behind the same QR code.
        </p>
        <div className="mt-4 flex flex-wrap justify-center gap-2">
          <Button variant="primary" onClick={() => fileInput.current?.click()}>Choose photos</Button>
          <Button onClick={() => folderInput.current?.click()}>Choose a folder</Button>
        </div>
        <p className="mt-3 text-xs text-muted">JPEG or PNG · or drag photos here</p>
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
      </div>

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
