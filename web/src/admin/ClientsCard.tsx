import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError, openZip } from '../lib/api'
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

const clientLoginUrl = () => `${window.location.origin}/client`

function clientMessage(c: Credentials, eventName: string) {
  return `Hi ${c.name}! Your photos from ${eventName} are ready.\n\nView all photos in full quality and choose your favourites for the album:\n${clientLoginUrl()}\n\nEmail: ${c.email}\nTemporary password: ${c.password}\n\nYou'll choose your own password when you first log in.`
}

/**
 * The couple's login for this event (one per event: both of them share it) and their album selection.
 * They see every photo by category, download originals, and select their favourites for the album.
 */
export function ClientsCard({ eventId, eventName, refreshKey, onClients }: {
  eventId: string
  eventName: string
  refreshKey: number
  /** The event page shows the login in its header too. */
  onClients?: (clients: EventClient[]) => void
}) {
  const [clients, setClients] = useState<EventClient[] | null>(null)
  const [album, setAlbum] = useState<AlbumSelection | null>(null)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState(temporaryPassword)
  const [limit, setLimit] = useState('')
  const [credentials, setCredentials] = useState<Credentials | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [zipping, setZipping] = useState(false)
  const [confirm, confirmDialog] = useConfirm()

  const load = useCallback(() => {
    adminApi.clients(eventId).then((list) => {
      setClients(list)
      onClients?.(list)
    }).catch(() => {})
    adminApi.album(eventId).then((a) => {
      setAlbum(a)
      setLimit(a.limit ? String(a.limit) : '')
    }).catch(() => {})
  }, [eventId, onClients])
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
      await adminApi.createClient(eventId, { name: name.trim(), email: email.trim(), password, phone: phone.trim() || undefined })
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

  /** The password is stored scrambled, so sending the login again means a new temporary password. */
  const sendAgain = async (c: EventClient) => {
    const ok = await confirm({
      title: `Send ${c.name} their login again?`,
      body: 'A new temporary password is created and the old one stops working. You then send it on WhatsApp or email.',
      confirmLabel: 'Create new password',
    })
    if (!ok) return
    const fresh = temporaryPassword()
    await adminApi.updateClient(eventId, c.id, { newPassword: fresh })
    setCredentials({ name: c.name, email: c.email, password: fresh, phone: c.phone ?? undefined })
    load()
  }

  const toggle = async (c: EventClient) => {
    await adminApi.updateClient(eventId, c.id, { disabled: !c.disabled })
    load()
  }

  const remove = async (c: EventClient) => {
    const ok = await confirm({ title: `Delete ${c.name}'s login?`, body: 'They can no longer log in. Photos they selected stay in the album, and you can create a new login.', confirmLabel: 'Delete login', danger: true })
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

  const downloadAlbum = async () => {
    setError(null)
    setZipping(true)
    try {
      const zip = await adminApi.prepareZip(eventId, { album: true })
      openZip(zip)
      setNote(`Downloading ${formatCount(zip.photos)} photos as ${zip.fileName}`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not start the download')
    } finally {
      setZipping(false)
    }
  }

  const copyLink = async () => {
    await navigator.clipboard?.writeText(clientLoginUrl())
    setNote('Login page link copied')
  }

  const selectedCount = album?.selected.length ?? 0
  return (
    <section id="client-login" className="scroll-mt-6">
      <Card title="Client login & album">
        {confirmDialog}

        {credentials && (
          <div className="mb-4 rounded-xl border border-emerald-200 bg-emerald-50/40 p-4">
            <p className="text-sm font-medium">Login for {credentials.name}: send it now (the password is shown only once)</p>
            <pre className="mt-2 overflow-x-auto rounded-lg bg-white p-3 text-sm whitespace-pre-wrap" data-testid="client-credentials">{clientMessage(credentials, eventName)}</pre>
            <SendButtons outgoing={outgoing(credentials)} onDone={() => setCredentials(null)} />
          </div>
        )}

        {clients && clients.length > 0 && (
          <div className="mb-2 space-y-3" data-testid="clients">
            {clients.map((c) => (
              <div key={c.id} className="rounded-xl border border-line p-4" data-testid="client-login">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="font-medium">{c.name}</p>
                  {c.disabled
                    ? <span className="rounded bg-red-50 px-1.5 py-0.5 text-[11px] text-red-700">Turned off</span>
                    : c.lastLoginAt
                      ? <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-[11px] text-emerald-800">Active · last seen {timeAgo(c.lastLoginAt)}</span>
                      : <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[11px] text-amber-800">Not logged in yet</span>}
                </div>
                <dl className="mt-3 grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
                  <div><dt className="text-xs text-muted">Login email</dt><dd className="break-all">{c.email}</dd></div>
                  <div><dt className="text-xs text-muted">WhatsApp</dt><dd>{c.phone || <span className="text-muted">Not added</span>}</dd></div>
                  <div className="sm:col-span-2">
                    <dt className="text-xs text-muted">Login page</dt>
                    <dd className="flex flex-wrap items-center gap-2">
                      <a href={clientLoginUrl()} target="_blank" rel="noopener noreferrer" className="break-all underline underline-offset-4">{clientLoginUrl().replace(/^https?:\/\//, '')}</a>
                      <button type="button" onClick={copyLink} className="text-xs text-muted underline underline-offset-2 hover:text-ink">Copy</button>
                    </dd>
                  </div>
                  <div className="sm:col-span-2">
                    <dt className="text-xs text-muted">Password</dt>
                    <dd className="text-muted">{c.mustChangePassword ? 'Temporary password: they choose their own at first login' : 'Their own password'}</dd>
                  </div>
                </dl>
                <div className="mt-3 flex flex-wrap gap-2">
                  <Button variant="primary" onClick={() => sendAgain(c)}>Send login again</Button>
                  <Button variant="ghost" onClick={() => toggle(c)}>{c.disabled ? 'Turn on' : 'Turn off'}</Button>
                  <Button variant="ghost" className="text-red-700" onClick={() => remove(c)}>Delete login</Button>
                </div>
              </div>
            ))}
            <p className="text-xs text-muted">One login per event: the couple shares it, so both see the same photos and album.</p>
          </div>
        )}

        {clients && clients.length === 0 && (
          <>
            <p className="mb-4 text-sm text-muted">
              Give the couple their login: they see every photo by category, download originals, and select their favourites for the album. One login per event, shared by the couple.
            </p>
            <form onSubmit={create} className="grid gap-3 sm:grid-cols-2" aria-label="Create the client login">
              <Field label="Client name">
                <input className={inputClass} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Arun & Priya" />
              </Field>
              <Field label="Client email">
                <input className={inputClass} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="couple@gmail.com" />
              </Field>
              <Field label="WhatsApp number (optional)">
                <input className={inputClass} type="tel" inputMode="tel" maxLength={20} value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="98765 43210" />
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
          </>
        )}

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
                <Button variant="primary" onClick={downloadAlbum} disabled={zipping}>
                  {zipping ? 'Preparing…' : `Download all ${formatCount(selectedCount)} (.zip)`}
                </Button>
                <Button onClick={copyNames}>Copy file names</Button>
                <Button onClick={() => saveBlob(new Blob([fileNames() + '\n'], { type: 'text/plain' }), `${eventName.replace(/[^\w-]+/g, '-')}-album-selection.txt`)}>
                  Download list (.txt)
                </Button>
              </div>
              <p className="mt-2 text-xs text-muted">The ZIP has the original files where you uploaded them. Tip: filter the Photos list by “Couple's selection” to see them all.</p>
            </>
          )}
          {note && <p role="status" className="mt-2 text-sm text-emerald-700">{note}</p>}
          <div className="mt-2"><ErrorText>{error}</ErrorText></div>
        </div>
      </Card>
    </section>
  )
}
