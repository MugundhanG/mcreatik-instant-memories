import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import mkLogo from '../assets/mk-logo.webp'
import { ForgotPassword } from '../shared/ForgotPassword'
import { auth, useSession } from './auth'
import { Button, ErrorText, Field, inputClass } from './ui'

export default function LoginPage() {
  const session = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [forgot, setForgot] = useState(false)

  if (session) return <Navigate to="/admin" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      auth.signIn(await adminApi.login(email, password))
      navigate((location.state as { from?: string } | null)?.from ?? '/admin', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Cannot reach the server')
    } finally {
      setBusy(false)
    }
  }

  if (forgot) {
    return (
      <main className="flex min-h-dvh items-center justify-center bg-stone-50 px-4">
        <div className="w-full max-w-sm space-y-5 rounded-2xl border border-line bg-white p-7">
          <div>
            <p className="font-display text-2xl font-semibold">McreatiK</p>
            <p className="text-sm text-muted">Instant Memories · Forgot password</p>
          </div>
          <ForgotPassword request={adminApi.forgotPassword} initialEmail={email} onBack={() => setForgot(false)} />
        </div>
      </main>
    )
  }

  return (
    <main className="flex min-h-dvh items-center justify-center bg-stone-50 px-4">
      <form onSubmit={submit} className="w-full max-w-sm space-y-5 rounded-2xl border border-line bg-white p-7">
        <div>
          <img src={mkLogo} alt="" className="mb-3 h-10 w-auto" width={63} height={40} />
          <p className="font-display text-2xl font-semibold">McreatiK</p>
          <p className="text-sm text-muted">Instant Memories · Staff sign in</p>
        </div>
        <Field label="Email">
          <input className={inputClass} type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </Field>
        <Field label="Password">
          <input className={inputClass} type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </Field>
        <ErrorText>{error}</ErrorText>
        <Button type="submit" variant="primary" disabled={busy} className="w-full">
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
        <button type="button" onClick={() => setForgot(true)} className="w-full text-center text-sm text-muted underline underline-offset-2 hover:text-ink">
          Forgot password?
        </button>
      </form>
    </main>
  )
}
