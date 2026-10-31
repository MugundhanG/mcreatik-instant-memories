import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatBytes, formatCount, formatEventDate } from '../lib/format'
import type { Studio } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { useConfirm } from './useConfirm'

/** Readable temporary password: no look-alike characters (0/O, 1/l). */
function temporaryPassword() {
  const chars = 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(12))
  return Array.from(bytes, (b) => chars[b % chars.length]).join('')
}

interface Credentials {
  name: string
  email: string
  password: string
  /** WhatsApp number typed when creating the login (not stored); without it WhatsApp asks whom to send to. */
  phone?: string
}

/** The message the super admin sends the studio (WhatsApp/email). */
function loginMessage(c: Credentials) {
  return `Your McreatiK Live Gallery login for ${c.name}\n\nDashboard: ${window.location.origin}/admin\nEmail: ${c.email}\nTemporary password: ${c.password}\n\nYou'll be asked to choose your own password when you first log in.`
}

/** Digits for wa.me: a 10-digit Indian mobile gets the 91 country code. */
function whatsappNumber(phone: string | undefined) {
  const digits = (phone ?? '').replace(/\D/g, '').replace(/^0+/, '')
  return digits.length === 10 ? `91${digits}` : digits
}

/** Opens WhatsApp (app or web) with the message ready to send. */
function whatsappLink(c: Credentials) {
  const to = whatsappNumber(c.phone)
  return `https://wa.me/${to}?text=${encodeURIComponent(loginMessage(c))}`
}

/** Opens the default mail app addressed to the studio. */
function emailLink(c: Credentials) {
  const subject = `Your McreatiK Live Gallery login for ${c.name}`
  return `mailto:${encodeURIComponent(c.email)}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(loginMessage(c))}`
}

/** Super admin: client studio logins, their usage, password resets and access. */
export default function StudiosPage() {
  const [studios, setStudios] = useState<Studio[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [password, setPassword] = useState(temporaryPassword)
  const [busy, setBusy] = useState(false)
  const [credentials, setCredentials] = useState<Credentials | null>(null)
  const [copied, setCopied] = useState(false)
  const [confirm, confirmDialog] = useConfirm()

  const load = useCallback(() => adminApi.studios().then(setStudios).catch((e: Error) => setError(e.message)), [])
  useEffect(() => {
    load()
  }, [load])

  const create = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await adminApi.createStudio({ name: name.trim(), email: email.trim(), password })
      setCredentials({ name: name.trim(), email: email.trim(), password, phone: phone.trim() })
      setCopied(false)
      setName('')
      setEmail('')
      setPhone('')
      setPassword(temporaryPassword())
      load()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the studio')
    } finally {
      setBusy(false)
    }
  }

  const resetPassword = async (s: Studio) => {
    const ok = await confirm({
      title: `New password for ${s.name}?`,
      body: 'Their current password stops working. You get a new temporary password to send them; they choose their own at the next login.',
      confirmLabel: 'Create new password',
    })
    if (!ok) return
    const fresh = temporaryPassword()
    await adminApi.updateStudio(s.id, { newPassword: fresh })
    setCredentials({ name: s.name, email: s.email, password: fresh })
    setCopied(false)
    load()
  }

  const toggleDisabled = async (s: Studio) => {
    if (!s.disabled) {
      const ok = await confirm({
        title: `Disable ${s.name}?`,
        body: 'They are logged out at once and cannot log in. Their events, galleries and photos stay online for guests. You can enable the login again any time.',
        confirmLabel: 'Disable login',
        danger: true,
      })
      if (!ok) return
    }
    await adminApi.updateStudio(s.id, { disabled: !s.disabled })
    load()
  }

  const copy = async () => {
    if (!credentials) return
    try {
      await navigator.clipboard.writeText(loginMessage(credentials))
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  return (
    <div className="space-y-6">
      {confirmDialog}
      <h1 className="text-xl font-semibold">Studios</h1>

      {credentials && (
        <Card title={`Login for ${credentials.name}`} className="border-emerald-200">
          <p className="text-sm text-muted">Send this to the studio. The password is shown only now.</p>
          <pre className="mt-3 overflow-x-auto rounded-lg bg-stone-50 p-3 text-sm whitespace-pre-wrap" data-testid="studio-credentials">{loginMessage(credentials)}</pre>
          <div className="mt-3 flex flex-wrap gap-2">
            <a href={whatsappLink(credentials)} target="_blank" rel="noopener noreferrer"
              className="inline-flex items-center gap-2 rounded-lg bg-[#25d366] px-4 py-2 text-sm font-medium text-white hover:bg-[#1ebe5b]">
              <WhatsAppIcon /> Send on WhatsApp
            </a>
            <a href={emailLink(credentials)} className="inline-flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-2 text-sm font-medium hover:bg-stone-50">
              <MailIcon /> Send email
            </a>
            <Button onClick={copy}>{copied ? 'Copied ✓' : 'Copy message'}</Button>
            <Button variant="ghost" onClick={() => setCredentials(null)}>Done</Button>
          </div>
          {!credentials.phone && <p className="mt-2 text-xs text-muted">WhatsApp will ask which chat to send it to.</p>}
        </Card>
      )}

      <Card title="Add a studio">
        <form onSubmit={create} className="grid gap-4 sm:grid-cols-2">
          <Field label="Studio name" hint="Shown on their dashboard">
            <input className={inputClass} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Lens & Light Studio" />
          </Field>
          <Field label="Login email">
            <input className={inputClass} type="email" required value={email} onChange={(e) => setEmail(e.target.value)} placeholder="owner@studio.com" />
          </Field>
          <Field label="WhatsApp number (optional)" hint="Only to open the right chat; not saved">
            <input className={inputClass} type="tel" inputMode="tel" value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="98765 43210" />
          </Field>
          <Field label="Temporary password" hint="They must change it when they first log in">
            <div className="flex gap-2">
              <input className={`${inputClass} font-mono`} required minLength={10} value={password} onChange={(e) => setPassword(e.target.value)} />
              <Button onClick={() => setPassword(temporaryPassword())}>New</Button>
            </div>
          </Field>
          <div className="flex items-end justify-end">
            <Button type="submit" variant="primary" disabled={busy}>{busy ? 'Creating…' : 'Create studio login'}</Button>
          </div>
        </form>
        <div className="mt-3"><ErrorText>{error}</ErrorText></div>
      </Card>

      <Card title="All logins">
        {!studios && !error && <ShutterLoader className="py-8" />}
        <ul className="divide-y divide-line" data-testid="studios">
          {studios?.map((s) => {
            const me = s.role === 'SUPER_ADMIN'
            return (
              <li key={s.id} className="flex flex-wrap items-center gap-x-6 gap-y-2 py-3">
                <div className="min-w-48 flex-1">
                  <p className="font-medium">
                    {s.name}
                    {me && <span className="ml-2 rounded bg-ink px-1.5 py-0.5 text-[10px] text-white">McreatiK · super admin</span>}
                    {s.disabled && <span className="ml-2 rounded bg-red-50 px-1.5 py-0.5 text-[10px] text-red-700">Disabled</span>}
                    {!s.disabled && s.mustChangePassword && <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-800">Not logged in yet</span>}
                  </p>
                  <p className="text-xs text-muted">{s.email} · since {formatEventDate(s.createdAt.slice(0, 10))}</p>
                </div>
                <div className="flex gap-5 text-sm tabular-nums">
                  <span><b>{formatCount(s.events)}</b> <span className="text-muted">events</span></span>
                  <span><b>{formatCount(s.photos)}</b> <span className="text-muted">photos</span></span>
                  <span><b>{formatBytes(s.storageBytes)}</b> <span className="text-muted">stored</span></span>
                </div>
                {!me && (
                  <div className="flex gap-2">
                    <Button onClick={() => resetPassword(s)}>Reset password</Button>
                    <Button variant={s.disabled ? 'secondary' : 'danger'} onClick={() => toggleDisabled(s)}>{s.disabled ? 'Enable' : 'Disable'}</Button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      </Card>
    </div>
  )
}

function WhatsAppIcon() {
  return (
    <svg viewBox="0 0 24 24" className="size-4" fill="currentColor" aria-hidden="true">
      <path d="M12 2a10 10 0 0 0-8.6 15.1L2 22l5-1.3A10 10 0 1 0 12 2Zm0 18.2a8.2 8.2 0 0 1-4.2-1.2l-.3-.2-3 .8.8-2.9-.2-.3A8.2 8.2 0 1 1 12 20.2Zm4.5-6.1c-.2-.1-1.5-.7-1.7-.8-.2-.1-.4-.1-.6.1l-.8 1c-.1.2-.3.2-.5.1a6.7 6.7 0 0 1-3.3-2.9c-.2-.4.2-.4.7-1.3.1-.2 0-.3 0-.4l-.8-1.8c-.2-.5-.4-.4-.6-.4h-.5a1 1 0 0 0-.7.3 3 3 0 0 0-.9 2.2 5.1 5.1 0 0 0 1.1 2.7 11.7 11.7 0 0 0 4.5 4c1.7.7 2.3.8 3.2.6.5-.1 1.5-.6 1.7-1.2.2-.6.2-1.1.2-1.2-.1-.1-.3-.2-.5-.3Z" />
    </svg>
  )
}

function MailIcon() {
  return (
    <svg viewBox="0 0 24 24" className="size-4" fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="m3.5 6.5 8.5 6.5 8.5-6.5" />
    </svg>
  )
}
