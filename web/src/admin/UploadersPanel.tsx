import { useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount, timeAgo } from '../lib/format'
import type { UploaderCredentials, UploaderStats } from '../lib/types'
import { Button, Card, ErrorText, inputClass, UploaderStatusDot } from './ui'

function CredentialsDialog({ credentials, onClose }: { credentials: UploaderCredentials; onClose: () => void }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    await navigator.clipboard?.writeText(credentials.connectionCode)
    setCopied(true)
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="dialog" aria-modal="true" aria-label="Uploader connection code">
      <div className="w-full max-w-lg rounded-2xl bg-white p-6 shadow-xl">
        <h3 className="text-lg font-semibold">Connect “{credentials.name}”</h3>
        <ol className="mt-3 list-decimal space-y-1 pl-5 text-sm text-muted">
          <li>Open McreatiK Uploader on the laptop for this camera.</li>
          <li>Paste this connection code.</li>
          <li>Choose the folder the camera sends photos to.</li>
        </ol>
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
    if (!confirm(`Create a new code for “${u.name}”? The current code stops working immediately.`)) return
    setCredentials(await adminApi.rotateUploader(u.id))
    onChange()
  }

  const remove = async (u: UploaderStats) => {
    if (!confirm(`Remove “${u.name}”? Its photos stay in the gallery.`)) return
    await adminApi.deleteUploader(u.id)
    onChange()
  }

  return (
    <Card title="Cameras & uploaders">
      {uploaders.length === 0 && <p className="text-sm text-muted">No uploaders yet. Add one per camera.</p>}
      <ul className="divide-y divide-line">
        {uploaders.map((u) => (
          <li key={u.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3" data-testid="uploader-row">
            <div className="flex min-w-40 flex-1 items-center gap-3">
              <UploaderStatusDot status={u.status} />
              <div>
                <p className="text-sm font-medium">{u.name}</p>
                <p className="text-xs text-muted">
                  {u.status === 'ONLINE' ? 'Online' : u.status === 'ERROR' ? 'Error' : 'Offline'} · last seen {timeAgo(u.lastSeenAt)}
                </p>
              </div>
            </div>
            <div className="text-right text-sm">
              <p><span className="font-semibold tabular-nums">{formatCount(u.photosUploaded)}</span>{' '}
                <span className="text-muted">{u.photosUploaded === 1 ? 'photo' : 'photos'}</span></p>
              {u.queuePending > 0 && <p className="text-xs text-amber-700">{formatCount(u.queuePending)} waiting on laptop</p>}
              {u.queueFailed > 0 && <p className="text-xs text-red-700">{formatCount(u.queueFailed)} failed on laptop</p>}
            </div>
            <div className="flex gap-1">
              <Button variant="ghost" onClick={() => rotate(u)}>New code</Button>
              <Button variant="ghost" onClick={() => remove(u)}>Remove</Button>
            </div>
            {u.lastError && <p className="w-full text-xs text-red-700">{u.lastError}</p>}
          </li>
        ))}
      </ul>
      <form onSubmit={add} className="mt-4 flex gap-2 border-t border-line pt-4">
        <input className={inputClass} placeholder={`Camera ${uploaders.length + 1}`} value={name} maxLength={120} onChange={(e) => setName(e.target.value)} aria-label="Uploader name" />
        <Button type="submit" variant="primary" className="shrink-0">Add uploader</Button>
      </form>
      <ErrorText>{error}</ErrorText>
      {credentials && <CredentialsDialog credentials={credentials} onClose={() => setCredentials(null)} />}
    </Card>
  )
}
