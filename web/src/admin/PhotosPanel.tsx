import { useEffect, useState } from 'react'
import { adminApi } from '../lib/api'
import type { AdminPhoto } from '../lib/types'
import { Button, Card } from './ui'
import { useConfirm } from './useConfirm'

const statusLabel: Record<AdminPhoto['status'], string> = {
  QUEUED: 'Queued',
  UPLOADING: 'Uploading',
  UPLOADED: 'Processing',
  PROCESSING: 'Processing',
  READY: 'Ready',
  FAILED: 'Failed',
}

export function PhotosPanel({ eventId, coverPhotoId, refreshKey, onCoverChange }: { eventId: string; coverPhotoId: string | null; refreshKey: number; onCoverChange: (id: string) => void }) {
  const [photos, setPhotos] = useState<AdminPhoto[]>([])
  const [cursor, setCursor] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()

  // Reload the newest page whenever the number of processed photos changes.
  useEffect(() => {
    let active = true
    adminApi
      .photos(eventId)
      .then((page) => {
        if (!active) return
        setPhotos(page.photos)
        setCursor(page.nextCursor)
      })
      .catch(() => {})
    return () => {
      active = false
    }
  }, [eventId, refreshKey])

  const more = async () => {
    if (!cursor) return
    const page = await adminApi.photos(eventId, cursor)
    setPhotos((p) => [...p, ...page.photos])
    setCursor(page.nextCursor)
  }

  const remove = async (p: AdminPhoto) => {
    const ok = await confirm({
      title: 'Delete this photo?',
      body: `${p.originalFileName} is removed from the gallery, including from guests' open screens. This cannot be undone.`,
      confirmLabel: 'Delete photo',
      danger: true,
    })
    if (!ok) return
    await adminApi.deletePhoto(p.id)
    setPhotos((list) => list.filter((x) => x.id !== p.id))
  }

  const original = async (p: AdminPhoto) => {
    const { url } = await adminApi.originalUrl(p.id)
    window.open(url, '_blank', 'noopener')
  }

  return (
    <Card title="Photos" action={<span className="text-xs text-muted">Newest first</span>}>
      {photos.length === 0 && <p className="text-sm text-muted">No photos yet.</p>}
      <ul className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
        {photos.map((p) => (
          <li key={p.id} className="group">
            <div className="relative aspect-square overflow-hidden rounded-lg bg-stone-100">
              {p.thumbnailUrl ? (
                <img src={p.thumbnailUrl} alt={p.originalFileName} loading="lazy" className="size-full object-cover" />
              ) : (
                <div className="flex size-full items-center justify-center text-xs text-muted">{statusLabel[p.status]}</div>
              )}
              {p.id === coverPhotoId && <span className="absolute top-1.5 left-1.5 rounded bg-ink px-1.5 py-0.5 text-[10px] text-white">Cover</span>}
              {p.status !== 'READY' && (
                <span className={`absolute right-1.5 bottom-1.5 rounded px-1.5 py-0.5 text-[10px] ${p.status === 'FAILED' ? 'bg-red-600 text-white' : 'bg-white/90'}`}>
                  {statusLabel[p.status]}
                </span>
              )}
            </div>
            <p className="mt-1 truncate text-xs text-muted" title={p.failureReason ?? p.originalFileName}>
              {p.status === 'FAILED' ? p.failureReason : p.originalFileName}
            </p>
            <div className="mt-1 flex flex-wrap gap-x-2 text-xs">
              {p.status === 'READY' && p.id !== coverPhotoId && (
                <button type="button" className="text-muted hover:text-ink" onClick={() => onCoverChange(p.id)}>Cover</button>
              )}
              <button type="button" className="text-muted hover:text-ink" onClick={() => original(p)}>Original</button>
              <button type="button" className="text-red-700 hover:underline" onClick={() => remove(p)}>Delete</button>
            </div>
          </li>
        ))}
      </ul>
      {confirmDialog}
      {cursor && (
        <div className="mt-4 text-center">
          <Button onClick={more}>Load more</Button>
        </div>
      )}
    </Card>
  )
}
