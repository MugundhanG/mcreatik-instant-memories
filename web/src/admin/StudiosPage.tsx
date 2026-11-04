import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatBytes, formatCount, formatEventDate } from '../lib/format'
import type { Plan, Studio, StudioBilling } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { BillingSettingsCard, PaymentsToCheck, StudioPlan } from './BillingAdmin'
import { PLAN_LABEL } from './plans'
import { BrandingCard } from './BrandingCard'
import { SendButtons } from './SendButtons'
import { temporaryPassword, type Outgoing } from './sendLinks'
import { useConfirm } from './useConfirm'


interface Credentials {
  name: string
  email: string
  password: string
  /** WhatsApp number typed when creating the login (not stored); without it WhatsApp asks whom to send to. */
  phone?: string
}

/** The message the super admin sends the studio (WhatsApp/email). */
function loginMessage(c: Credentials) {
  return `Your McreatiK Instant Memories login for ${c.name}\n\nDashboard: ${window.location.origin}/admin\nEmail: ${c.email}\nTemporary password: ${c.password}\n\nYou'll be asked to choose your own password when you first log in.`
}

const outgoing = (c: Credentials): Outgoing => ({
  email: c.email,
  phone: c.phone,
  subject: `Your McreatiK Instant Memories login for ${c.name}`,
  message: loginMessage(c),
})

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
  const [plan, setPlan] = useState<Plan>('MONTHLY')
  const [billing, setBilling] = useState<Map<string, StudioBilling>>(new Map())
  const [brandingFor, setBrandingFor] = useState<string | null>(null)
  const [confirm, confirmDialog] = useConfirm()
  const [adding, setAdding] = useState(false)
  const clientStudios = studios?.filter((s) => s.role !== 'SUPER_ADMIN') ?? []
  // The form is for occasional use: hidden behind "Add studio", but open while there are no studios yet.
  const showForm = adding || (studios !== null && clientStudios.length === 0)

  const load = useCallback(() => {
    adminApi.studios().then(setStudios).catch((e: Error) => setError(e.message))
    adminApi.studioBilling().then((list) => setBilling(new Map(list.map((b) => [b.studioId, b])))).catch(() => {})
  }, [])
  useEffect(() => {
    load()
  }, [load])

  const create = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await adminApi.createStudio({ name: name.trim(), email: email.trim(), password, plan })
      setCredentials({ name: name.trim(), email: email.trim(), password, phone: phone.trim() })
      setName('')
      setEmail('')
      setPhone('')
      setPassword(temporaryPassword())
      setAdding(false)
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

  return (
    <div className="space-y-6">
      {confirmDialog}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Studios</h1>
          {studios && <p className="text-sm text-muted">{formatCount(clientStudios.length)} {clientStudios.length === 1 ? 'studio' : 'studios'}</p>}
        </div>
        {!showForm && <Button variant="primary" onClick={() => setAdding(true)}>Add studio</Button>}
      </div>

      {credentials && (
        <Card title={`Login for ${credentials.name}`} className="border-emerald-200">
          <p className="text-sm text-muted">Send this to the studio. The password is shown only now.</p>
          <pre className="mt-3 overflow-x-auto rounded-lg bg-stone-50 p-3 text-sm whitespace-pre-wrap" data-testid="studio-credentials">{loginMessage(credentials)}</pre>
          <SendButtons outgoing={outgoing(credentials)} onDone={() => setCredentials(null)} />
        </Card>
      )}

      {showForm && (
        <Card className="anim-drop-in" title="Add a studio" action={clientStudios.length > 0 && <button type="button" onClick={() => setAdding(false)} className="text-sm text-muted hover:text-ink">Cancel</button>}>
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
            <Field label="Plan" hint="Change it any time in the studio list">
              <select className={inputClass} value={plan} onChange={(e) => setPlan(e.target.value as Plan)}>
                {(Object.keys(PLAN_LABEL) as Plan[]).map((p) => <option key={p} value={p}>{PLAN_LABEL[p]}</option>)}
              </select>
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
      )}

      <PaymentsToCheck onChange={load} />

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
                    {me && <span className="ml-2 rounded bg-ink px-1.5 py-0.5 text-xs text-white">McreatiK · super admin</span>}
                    {s.disabled && <span className="ml-2 rounded bg-red-50 px-1.5 py-0.5 text-xs text-red-700">Disabled</span>}
                    {!s.disabled && s.mustChangePassword && <span className="ml-2 rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-800">Not logged in yet</span>}
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
                    <Button onClick={() => setBrandingFor((id) => (id === s.id ? null : s.id))} aria-expanded={brandingFor === s.id}>Edit branding</Button>
                    <Button onClick={() => resetPassword(s)}>Reset password</Button>
                    <Button variant={s.disabled ? 'secondary' : 'danger'} onClick={() => toggleDisabled(s)}>{s.disabled ? 'Enable' : 'Disable'}</Button>
                  </div>
                )}
                {!me && billing.get(s.id) && <StudioPlan billing={billing.get(s.id)!} onChange={load} />}
                {!me && brandingFor === s.id && s.brand && (
                  <div className="w-full rounded-xl border border-line p-4">
                    <BrandingCard
                      embedded
                      brand={s.brand}
                      actions={{
                        update: (input) => adminApi.updateStudioBranding(s.id, input),
                        upload: (png) => adminApi.uploadStudioLogo(s.id, png),
                        remove: () => adminApi.removeStudioLogo(s.id),
                      }}
                      onSaved={() => load()}
                    />
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      </Card>

      <BillingSettingsCard />
    </div>
  )
}
