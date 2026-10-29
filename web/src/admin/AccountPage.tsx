import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import type { Account } from '../lib/types'
import { auth, useSession } from './auth'
import { BrandingCard } from './BrandingCard'
import { Button, Card, ErrorText, Field, inputClass } from './ui'

const MIN = 10

/** Change password. Studios land here first when McreatiK set their (temporary) password. */
export default function AccountPage() {
  const session = useSession()
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [repeat, setRepeat] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const forced = !!session?.mustChangePassword
  const [account, setAccount] = useState<Account | null>(null)
  useEffect(() => {
    adminApi.account().then(setAccount).catch(() => {})
  }, [])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setError(null)
    if (next.length < MIN) return setError(`Use at least ${MIN} characters`)
    if (next !== repeat) return setError('The two new passwords do not match')
    setBusy(true)
    try {
      await adminApi.changePassword(current, next)
      auth.update({ mustChangePassword: false })
      setDone(true)
      setCurrent('')
      setNext('')
      setRepeat('')
      if (forced) navigate('/admin', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not change the password')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto max-w-md space-y-4">
      <Card title="Your account">
        <p className="text-sm"><span className="font-medium">{session?.displayName}</span> · <span className="text-muted">{session?.email}</span></p>
      </Card>
      {/* McreatiK's own events use the built-in McreatiK design; studios set their own. */}
      {!forced && account && account.role === 'STUDIO' && <BrandingCard account={account} onChange={setAccount} />}
      <Card title={forced ? 'Choose your own password' : 'Change password'}>
        {forced && (
          <p className="mb-4 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
            Welcome! Your login was created with a temporary password. Choose your own to continue.
          </p>
        )}
        <form onSubmit={submit} className="space-y-4">
          <Field label={forced ? 'Temporary password' : 'Current password'}>
            <input className={inputClass} type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
          </Field>
          <Field label="New password" hint={`At least ${MIN} characters`}>
            <input className={inputClass} type="password" autoComplete="new-password" required value={next} onChange={(e) => setNext(e.target.value)} />
          </Field>
          <Field label="Repeat new password">
            <input className={inputClass} type="password" autoComplete="new-password" required value={repeat} onChange={(e) => setRepeat(e.target.value)} />
          </Field>
          <ErrorText>{error}</ErrorText>
          {done && <p role="status" className="text-sm text-emerald-700">Password changed.</p>}
          <div className="flex justify-end">
            <Button type="submit" variant="primary" disabled={busy}>{busy ? 'Saving…' : 'Save password'}</Button>
          </div>
        </form>
      </Card>
    </div>
  )
}
