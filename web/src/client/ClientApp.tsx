import { useState, type FormEvent } from 'react'
import { Navigate, Route, Routes, useNavigate } from 'react-router'
import { ApiError } from '../lib/api'
import { clientApi, clientSession, useClientSession } from './clientApi'
import ClientPortal from './ClientPortal'

const inputClass = 'h-11 w-full rounded-xl border border-line bg-white px-3.5 text-[15px] outline-none focus:border-ink focus:ring-1 focus:ring-ink'

function Shell({ title, subtitle, children }: { title: string; subtitle: string; children: React.ReactNode }) {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-[radial-gradient(ellipse_at_50%_20%,#fffdf8,#f3ead8)] px-4">
      <div className="w-full max-w-sm rounded-3xl bg-white p-7 shadow-[0_20px_60px_rgba(80,60,30,0.15)]">
        <p className="font-display text-3xl font-medium">{title}</p>
        <p className="mt-1 text-sm text-muted">{subtitle}</p>
        <div className="mt-6">{children}</div>
        <p className="mt-6 text-center text-xs text-muted"><a href="/privacy" className="underline underline-offset-2 hover:text-ink">Privacy &amp; security</a></p>
      </div>
    </main>
  )
}

function LoginPage() {
  const session = useClientSession()
  const navigate = useNavigate()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (session) return <Navigate to="/client" replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      clientSession.set(await clientApi.login(email, password))
      navigate('/client', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not log in')
    } finally {
      setBusy(false)
    }
  }
  return (
    <Shell title="Your photos" subtitle="Log in with the email and password your photographer sent you.">
      <form onSubmit={submit} className="space-y-4">
        <label className="block text-sm font-medium">Email
          <input className={`${inputClass} mt-1.5`} type="email" autoComplete="username" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="block text-sm font-medium">Password
          <input className={`${inputClass} mt-1.5`} type="password" autoComplete="current-password" required value={password} onChange={(e) => setPassword(e.target.value)} />
        </label>
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        <button type="submit" disabled={busy} className="h-11 w-full rounded-xl bg-ink text-sm font-medium text-white disabled:opacity-60">
          {busy ? 'Logging in…' : 'Log in'}
        </button>
      </form>
    </Shell>
  )
}

function PasswordPage() {
  const navigate = useNavigate()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [error, setError] = useState<string | null>(null)
  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (next.length < 10) return setError('Use at least 10 characters')
    try {
      const fresh = await clientApi.changePassword(current, next)
      clientSession.update({ ...(fresh ?? {}), mustChangePassword: false })
      navigate('/client', { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not change the password')
    }
  }
  return (
    <Shell title="Welcome!" subtitle="Choose your own password to continue.">
      <form onSubmit={submit} className="space-y-4">
        <label className="block text-sm font-medium">Temporary password
          <input className={`${inputClass} mt-1.5`} type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
        </label>
        <label className="block text-sm font-medium">New password <span className="font-normal text-muted">(at least 10 characters)</span>
          <input className={`${inputClass} mt-1.5`} type="password" autoComplete="new-password" required value={next} onChange={(e) => setNext(e.target.value)} />
        </label>
        {error && <p role="alert" className="text-sm text-red-700">{error}</p>}
        <button type="submit" className="h-11 w-full rounded-xl bg-ink text-sm font-medium text-white">Save and see my photos</button>
      </form>
    </Shell>
  )
}

function RequireClient({ children }: { children: React.ReactNode }) {
  const session = useClientSession()
  if (!session) return <Navigate to="/client/login" replace />
  if (session.mustChangePassword) return <Navigate to="/client/password" replace />
  return <>{children}</>
}

/** The couple's portal: /client/login, /client/password, /client. */
export default function ClientApp() {
  const session = useClientSession()
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route path="password" element={session ? <PasswordPage /> : <Navigate to="/client/login" replace />} />
      <Route index element={<RequireClient><ClientPortal /></RequireClient>} />
      <Route path="*" element={<Navigate to="/client" replace />} />
    </Routes>
  )
}
