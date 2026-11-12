import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatCount } from '../lib/format'
import type { Plan, Studio, StudioBilling } from '../lib/types'
import { ShutterLoader } from '../shared/ShutterLoader'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { PLAN_LABEL } from './plans'
import { StudioAccordionItem } from './StudioAccordionItem'
import { loginMessage, outgoing, type Credentials } from './studioLogin'
import { SendButtons } from './SendButtons'
import { temporaryPassword } from './sendLinks'
import { useConfirm } from './useConfirm'


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
  const [plan, setPlan] = useState<Plan>('PER_EVENT')
  const [billing, setBilling] = useState<Map<string, StudioBilling>>(new Map())
  const [openId, setOpenId] = useState<string | null>(null)
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
                {(Object.keys(PLAN_LABEL) as Plan[]).filter((p) => p !== 'MONTHLY').map((p) => <option key={p} value={p}>{PLAN_LABEL[p]}</option>)}
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

      <Card title="All logins">
        {!studios && !error && <ShutterLoader className="py-8" />}
        <ul className="divide-y divide-line" data-testid="studios">
          {studios?.map((s) =>
            s.role === 'SUPER_ADMIN' ? (
              <li key={s.id} className="flex items-center justify-between gap-3 py-3">
                <div className="min-w-0">
                  <p className="font-medium">{s.name} <span className="ml-1 rounded bg-ink px-1.5 py-0.5 text-xs text-white">McreatiK · super admin</span></p>
                  <p className="truncate text-xs text-muted">{s.email}</p>
                </div>
                <span className="shrink-0 text-sm tabular-nums"><b>{formatCount(s.events)}</b> <span className="text-muted">events</span></span>
              </li>
            ) : (
              <StudioAccordionItem key={s.id} studio={s} billing={billing.get(s.id)} open={openId === s.id}
                onToggle={() => setOpenId((id) => (id === s.id ? null : s.id))}
                confirm={confirm} reload={load} onError={setError} />
            ))}
        </ul>
      </Card>
    </div>
  )
}
