import { useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount, timeAgo } from '../lib/format'
import type { UploaderCredentials, UploaderStats } from '../lib/types'
import { Button, Card, ErrorText, inputClass, UploaderStatusDot } from './ui'
import { UPLOADER_DOWNLOADS } from './uploaderDownloads'
import { useConfirm } from './useConfirm'

const DOWNLOAD_LIST = [UPLOADER_DOWNLOADS.windows, UPLOADER_DOWNLOADS.macApple, UPLOADER_DOWNLOADS.macIntel]

function DownloadLinks() {
  return (
    <p className="text-xs text-muted">
      Download McreatiK Instant Memories Uploader:{' '}
      {DOWNLOAD_LIST.map((d, i) => (
        <span key={d.label}>
          {i > 0 && ' · '}
          <a href={d.href} className="font-medium text-ink underline underline-offset-2 hover:text-[#8a5a1c]">{d.label}</a>
        </span>
      ))}
    </p>
  )
}

function CredentialsDialog({ credentials, onClose }: { credentials: UploaderCredentials; onClose: () => void }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    await navigator.clipboard?.writeText(credentials.connectionCode)
    setCopied(true)
  }
  return (
    <div className="anim-fade-in fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true" aria-label="Uploader connection code">
      <div className="anim-dialog-in w-full max-w-lg rounded-2xl bg-white p-6 shadow-xl">
        <h3 className="text-lg font-semibold">Connect “{credentials.name}”</h3>
        <ol className="mt-3 list-decimal space-y-1 pl-5 text-sm text-muted">
          <li>Open McreatiK Instant Memories Uploader on the laptop for this camera (not installed yet? use a link below).</li>
          <li>Paste this connection code.</li>
          <li>Choose the folder the camera sends photos to.</li>
        </ol>
        <div className="mt-3"><DownloadLinks /></div>
        <textarea
          readOnly
          value={credentials.connectionCode}
          className="mt-4 h-24 w-full resize-none rounded-lg border border-line bg-stone-50 p-3 font-mono text-xs break-all"
          onFocus={(e) => e.currentTarget.select()}
        />
        <p className="mt-2 text-xs text-amber-700">
          This code is shown only once. Treat it like a password. If it leaks, use “New code” to revoke it.
        </p>
        <div className="mt-5 flex justify-end gap-2">
          <Button onClick={copy}>{copied ? 'Copied' : 'Copy code'}</Button>
          <Button variant="primary" onClick={onClose}>Done</Button>
        </div>
      </div>
    </div>
  )
}

export function UploadersPanel({ eventId, uploaders, onChange }: { eventId: string; uploaders: UploaderStats[]; onChange: () => void }) {
  const [name, setName] = useState('')
  const [credentials, setCredentials] = useState<UploaderCredentials | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()
  const [renaming, setRenaming] = useState<{ id: string; name: string } | null>(null)
  const [busy, setBusy] = useState<string | null>(null)

  const change = async (u: UploaderStats, changes: { name?: string; paused?: boolean }) => {
    setError(null)
    setBusy(u.id)
    try {
      await adminApi.updateUploader(u.id, changes)
      setRenaming(null)
      onChange()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not update the camera')
    } finally {
      setBusy(null)
    }
  }

  const saveName = (e: FormEvent, u: UploaderStats) => {
    e.preventDefault()
    const next = renaming?.name.trim() ?? ''
    if (!next || next === u.name) {
      setRenaming(null)
      return
    }
    void change(u, { name: next })
  }

  const add = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      setCredentials(await adminApi.createUploader(eventId, name.trim() || `Camera ${uploaders.length + 1}`))
      setName('')
      onChange()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not add uploader')
    }
  }

  const rotate = async (u: UploaderStats) => {
    const ok = await confirm({
      title: `New code for “${u.name}”?`,
      body: 'The current code stops working immediately. Paste the new code into the uploader on that laptop.',
      confirmLabel: 'Create new code',
    })
    if (!ok) return
    setCredentials(await adminApi.rotateUploader(u.id))
    onChange()
  }

  const remove = async (u: UploaderStats) => {
    const ok = await confirm({
      title: `Remove “${u.name}”?`,
      body: 'That laptop can no longer upload. Photos it already uploaded stay in the gallery.',
      confirmLabel: 'Remove uploader',
      danger: true,
    })
    if (!ok) return
    await adminApi.deleteUploader(u.id)
    onChange()
  }

  return (
    <Card title="Cameras & uploaders">
      {uploaders.length === 0 && <p className="text-sm text-muted">No uploaders yet. Add one per camera.</p>}
      {uploaders.length > 0 && (
        <p className="mb-1 text-xs text-muted">Pause a camera for private moments: its new photos stay on the laptop and never reach the gallery.</p>
      )}
      <ul className="divide-y divide-line">
        {uploaders.map((u) => (
          <li key={u.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3" data-testid="uploader-row">
            <div className="flex min-w-40 flex-1 items-center gap-3">
              {u.paused ? <span className="inline-block size-2.5 rounded-full bg-amber-500" aria-hidden="true" /> : <UploaderStatusDot status={u.status} />}
              <div className="min-w-0 flex-1">
                {renaming?.id === u.id ? (
                  <form onSubmit={(e) => saveName(e, u)} className="flex max-w-sm gap-2">
                    <input className={`${inputClass} h-9`} value={renaming.name} maxLength={60} autoFocus aria-label={`New name for ${u.name}`}
                      onChange={(e) => setRenaming({ id: u.id, name: e.target.value })}
                      onKeyDown={(e) => e.key === 'Escape' && setRenaming(null)} />
                    <Button type="submit" variant="primary" disabled={busy === u.id}>Save</Button>
                    <Button variant="ghost" onClick={() => setRenaming(null)}>Cancel</Button>
                  </form>
                ) : (
                  <p className="flex items-center gap-2 text-sm font-medium">
                    {u.name}
                    {u.paused && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[11px] font-semibold text-amber-800">Paused</span>}
                  </p>
                )}
                <p className="text-xs text-muted">
                  {u.paused ? 'Paused: new photos stay on the laptop, none go to the gallery'
                    : `${u.status === 'ONLINE' ? 'Online' : u.status === 'ERROR' ? 'Error' : 'Offline'} · last seen ${timeAgo(u.lastSeenAt)}`}
                </p>
              </div>
            </div>
            <div className="text-right text-sm">
              <p><span className="font-semibold tabular-nums">{formatCount(u.photosUploaded)}</span>{' '}
                <span className="text-muted">{u.photosUploaded === 1 ? 'photo' : 'photos'}</span></p>
              {u.queuePending > 0 && <p className="text-xs text-amber-700">{formatCount(u.queuePending)} waiting on laptop</p>}
              {u.queueFailed > 0 && <p className="text-xs text-red-700">{formatCount(u.queueFailed)} failed on laptop</p>}
            </div>
            <div className="flex flex-wrap gap-1">
              <Button variant={u.paused ? 'primary' : 'secondary'} disabled={busy === u.id} onClick={() => change(u, { paused: !u.paused })}>
                {u.paused ? 'Resume' : 'Pause'}
              </Button>
              <Button variant="ghost" onClick={() => setRenaming({ id: u.id, name: u.name })}>Rename</Button>
              <Button variant="ghost" onClick={() => rotate(u)}>New code</Button>
              <Button variant="ghost" onClick={() => remove(u)}>Remove</Button>
            </div>
            {u.lastError && <p className="w-full text-xs text-red-700">{u.lastError}</p>}
          </li>
        ))}
      </ul>
      <form onSubmit={add} className="mt-4 flex gap-2 border-t border-line pt-4">
        <input className={inputClass} placeholder={`Camera ${uploaders.length + 1}`} value={name} maxLength={60} onChange={(e) => setName(e.target.value)} aria-label="Uploader name" />
        <Button type="submit" variant="primary" className="shrink-0">Add uploader</Button>
      </form>
      <ErrorText>{error}</ErrorText>
      <div className="mt-3"><DownloadLinks /></div>
      {confirmDialog}
      {credentials && <CredentialsDialog credentials={credentials} onClose={() => setCredentials(null)} />}
    </Card>
  )
}
