import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import mkLogo from '../assets/mk-logo.webp'
import { ForgotPassword } from '../shared/ForgotPassword'
import { PolicyLinks } from '../shared/policies/PolicyLayout'
import { auth, useSession } from './auth'
import { Button, ErrorText, Field, inputClass } from './ui'
import { PasswordInput } from '../shared/PasswordInput'
import { emailError, requiredError } from '../shared/validate'

export default function LoginPage() {
  const session = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [touched, setTouched] = useState({ email: false, password: false })
  const [busy, setBusy] = useState(false)
  const [forgot, setForgot] = useState(false)

  if (session) return <Navigate to="/admin" replace />

  const emailMsg = touched.email ? emailError(email) : null
  const passwordMsg = touched.password ? requiredError(password, 'Enter your password') : null

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setTouched({ email: true, password: true })
    if (emailError(email) || requiredError(password, 'x')) return
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
      <form onSubmit={submit} noValidate className="w-full max-w-sm space-y-5 rounded-2xl border border-line bg-white p-6 shadow-sm sm:p-8">
        <div className="text-center">
          <img src={mkLogo} alt="" className="mx-auto mb-3 h-12 w-auto" width={76} height={48} />
          <h1 className="font-display text-2xl font-semibold">McreatiK</h1>
          <p className="mt-0.5 text-xs tracking-[0.2em] text-muted uppercase">Instant Memories</p>
          <p className="mt-4 text-sm text-muted">Sign in to your studio dashboard</p>
        </div>
        {error && <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-800">{error}</p>}
        <div>
          <Field label="Email">
            <input className={`${inputClass} ${emailMsg ? 'border-red-400' : ''}`} type="email" autoComplete="username" inputMode="email" autoCapitalize="off"
              aria-invalid={!!emailMsg} value={email} onChange={(e) => setEmail(e.target.value)} onBlur={() => setTouched((t) => ({ ...t, email: true }))} />
          </Field>
          <div className="min-h-11 pt-1"><ErrorText>{emailMsg}</ErrorText></div>
        </div>
        <div>
          <Field label="Password">
            <PasswordInput className={`${inputClass} ${passwordMsg ? 'border-red-400' : ''}`} autoComplete="current-password"
              aria-invalid={!!passwordMsg} value={password} onChange={(e) => setPassword(e.target.value)} onBlur={() => setTouched((t) => ({ ...t, password: true }))} />
          </Field>
          <div className="min-h-5 pt-1"><ErrorText>{passwordMsg}</ErrorText></div>
        </div>
        <Button type="submit" variant="primary" disabled={busy} className="w-full">
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
        <button type="button" onClick={() => setForgot(true)} className="w-full text-center text-sm text-muted underline underline-offset-2 hover:text-ink">
          Forgot password?
        </button>
        <PolicyLinks className="border-t border-line pt-4" />
      </form>
    </main>
  )
}
