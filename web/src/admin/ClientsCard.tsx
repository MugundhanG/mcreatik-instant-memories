import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount, timeAgo } from '../lib/format'
import type { AlbumSelection, EventClient } from '../lib/types'
import { saveBlob } from '../shared/saveBlob'
import { SendButtons } from './SendButtons'
import { temporaryPassword, type Outgoing } from './sendLinks'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { useConfirm } from './useConfirm'

interface Credentials {
  name: string
  email: string
  password: string
  phone?: string
}

function clientMessage(c: Credentials, eventName: string) {
  return `Hi ${c.name}! Your photos from ${eventName} are ready.\n\nView all photos in full quality and choose your favourites for the album:\n${window.location.origin}/client\n\nEmail: ${c.email}\nTemporary password: ${c.password}\n\nYou'll choose your own password when you first log in.`
}

/** The couple's logins (view everything in original quality, select for the album) and their selection. */
export function ClientsCard({ eventId, eventName, refreshKey }: { eventId: string; eventName: string; refreshKey: number }) {
  const [clients, setClients] = useState<EventClient[]>([])
  const [album, setAlbum] = useState<AlbumSelection | null>(null)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState(temporaryPassword)
  const [limit, setLimit] = useState('')
  const [credentials, setCredentials] = useState<Credentials | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()

  const load = useCallback(() => {
    adminApi.clients(eventId).then(setClients).catch(() => {})
    adminApi.album(eventId).then((a) => {
      setAlbum(a)
      setLimit(a.limit ? String(a.limit) : '')
    }).catch(() => {})
  }, [eventId])
  useEffect(() => {
    load()
  }, [load, refreshKey])

  const outgoing = (c: Credentials): Outgoing => ({
    email: c.email, phone: c.phone, subject: `Your photos from ${eventName}`, message: clientMessage(c, eventName),
  })

  const create = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    try {
      await adminApi.createClient(eventId, { name: name.trim(), email: email.trim(), password })
      setCredentials({ name: name.trim(), email: email.trim().toLowerCase(), password, phone: phone.trim() })
      setName('')
      setEmail('')
      setPhone('')
      setPassword(temporaryPassword())
      load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the login')
    }
  }

  const resetPassword = async (c: EventClient) => {
    const ok = await confirm({ title: `New password for ${c.name}?`, body: 'Their current password stops working. You get a new temporary password to send them.', confirmLabel: 'Create new password' })
    if (!ok) return
    const fresh = temporaryPassword()
    await adminApi.updateClient(eventId, c.id, { newPassword: fresh })
    setCredentials({ name: c.name, email: c.email, password: fresh })
    load()
  }

  const toggle = async (c: EventClient) => {
    await adminApi.updateClient(eventId, c.id, { disabled: !c.disabled })
    load()
  }

  const remove = async (c: EventClient) => {
    const ok = await confirm({ title: `Delete ${c.name}'s login?`, body: 'They can no longer log in. Photos they selected stay in the album.', confirmLabel: 'Delete login', danger: true })
    if (!ok) return
    await adminApi.deleteClient(eventId, c.id)
    load()
  }

  const saveLimit = async (e: FormEvent) => {
    e.preventDefault()
    const n = limit.trim() === '' ? null : Number(limit)
    if (n !== null && (!Number.isInteger(n) || n < 1)) return setError('The album limit must be a whole number')
    setError(null)
    await adminApi.setAlbumLimit(eventId, n)
    setNote(n ? `Album limit set to ${formatCount(n)} photos` : 'No album limit')
    load()
  }

  const fileNames = () => (album?.selected ?? []).map((p) => p.fileName).join('\n')
  const copyNames = async () => {
    await navigator.clipboard?.writeText(fileNames())
    setNote('File names copied: paste them into Lightroom or your album software')
  }

  const selectedCount = album?.selected.length ?? 0
  return (
    <Card title="Client logins & album">
      {confirmDialog}
      <p className="mb-4 text-sm text-muted">
        Give the couple their own login: they see every photo by category, download originals, and select their favourites for the album.
      </p>

      {credentials && (
        <div className="mb-4 rounded-xl border border-emerald-200 bg-emerald-50/40 p-4">
          <p className="text-sm font-medium">Login for {credentials.name}: send it now (the password is shown only once)</p>
          <pre className="mt-2 overflow-x-auto rounded-lg bg-white p-3 text-sm whitespace-pre-wrap" data-testid="client-credentials">{clientMessage(credentials, eventName)}</pre>
          <SendButtons outgoing={outgoing(credentials)} onDone={() => setCredentials(null)} />
        </div>
      )}

      {clients.length > 0 && (
        <ul className="mb-4 divide-y divide-line rounded-lg border border-line" data-testid="clients">
          {clients.map((c) => (
            <li key={c.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-3 py-2.5">
              <div className="min-w-40 flex-1">
                <p className="text-sm font-medium">
                  {c.name}
                  {c.disabled && <span className="ml-2 rounded bg-red-50 px-1.5 py-0.5 text-[10px] text-red-700">Disabled</span>}
                  {!c.disabled && !c.lastLoginAt && <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-800">Not logged in yet</span>}
                </p>
                <p className="text-xs text-muted">{c.email}{c.lastLoginAt ? ` · last seen ${timeAgo(c.lastLoginAt)}` : ''}</p>
              </div>
              <div className="flex gap-1">
                <Button variant="ghost" onClick={() => resetPassword(c)}>Reset password</Button>
                <Button variant="ghost" onClick={() => toggle(c)}>{c.disabled ? 'Enable' : 'Disable'}</Button>
                <Button variant="ghost" className="text-red-700" onClick={() => remove(c)}>Delete</Button>
              </div>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={create} className="grid gap-3 sm:grid-cols-2" aria-label="Add a client login">
        <Field label="Client name">
          <input className={inputClass} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Arun & Priya" />
        </Field>
        <Field label="Client email">
          <input className={inputClass} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="couple@gmail.com" />
        </Field>
        <Field label="WhatsApp number (optional)">
          <input className={inputClass} type="tel" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="98765 43210" />
        </Field>
        <Field label="Temporary password">
          <div className="flex gap-2">
            <input className={`${inputClass} font-mono`} required minLength={10} value={password} onChange={(e) => setPassword(e.target.value)} />
            <Button onClick={() => setPassword(temporaryPassword())}>New</Button>
          </div>
        </Field>
        <div className="sm:col-span-2 flex justify-end">
          <Button type="submit" variant="primary">Create client login</Button>
        </div>
      </form>

      <div className="mt-6 border-t border-line pt-4">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <p className="text-sm font-medium">Album selection</p>
            <p className="text-sm text-muted tabular-nums" data-testid="album-count">
              <b className="text-ink">{formatCount(selectedCount)}</b>{album?.limit ? ` of ${formatCount(album.limit)}` : ''} photos selected by the couple
            </p>
          </div>
          <form onSubmit={saveLimit} className="flex items-end gap-2">
            <Field label="Album limit (optional)">
              <input className={`${inputClass} w-32`} inputMode="numeric" value={limit} onChange={(e) => setLimit(e.target.value)} placeholder="No limit" />
            </Field>
            <Button type="submit">Save</Button>
          </form>
        </div>
        {selectedCount > 0 && (
          <>
            <ul className="mt-3 grid grid-cols-6 gap-1.5 sm:grid-cols-10" data-testid="album-thumbs">
              {album!.selected.slice(0, 20).map((p) => (
                <li key={p.photoId}>
                  {p.thumbnailUrl ? <img src={p.thumbnailUrl} alt={p.fileName} title={p.fileName} className="aspect-square w-full rounded object-cover" /> : <div className="aspect-square rounded bg-stone-100" />}
                </li>
              ))}
            </ul>
            <div className="mt-3 flex flex-wrap gap-2">
              <Button onClick={copyNames}>Copy file names</Button>
              <Button onClick={() => saveBlob(new Blob([fileNames() + '\n'], { type: 'text/plain' }), `${eventName.replace(/[^\w-]+/g, '-')}-album-selection.txt`)}>
                Download list (.txt)
              </Button>
            </div>
            <p className="mt-2 text-xs text-muted">Tip: filter the Photos list by “Couple's selection” to see them all.</p>
          </>
        )}
        {note && <p role="status" className="mt-2 text-sm text-emerald-700">{note}</p>}
        <div className="mt-2"><ErrorText>{error}</ErrorText></div>
      </div>
    </Card>
  )
}
