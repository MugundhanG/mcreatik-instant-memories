import { useState, type FormEvent } from 'react'
import { ApiError } from '../lib/api'

/**
 * "Forgot password?": asks for the login email and has a temporary password sent by email. The answer is the
 * same whether or not the email has an account, so nobody can use this to find out who has one.
 */
export function ForgotPassword({ request, onBack, initialEmail = '' }: {
  request: (email: string) => Promise<unknown>
  onBack: () => void
  initialEmail?: string
}) {
  const [email, setEmail] = useState(initialEmail)
  const [busy, setBusy] = useState(false)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await request(email.trim())
      setSent(true)
    } catch (err) {
      setError(err instanceof ApiError && err.status === 429 ? 'Too many requests. Please wait a minute and try again.' : 'Cannot reach the server. Please try again.')
    } finally {
      setBusy(false)
    }
  }

  if (sent) {
    return (
      <div className="space-y-4" role="status" data-testid="forgot-sent">
        <p className="text-sm font-medium text-ink">Check your email</p>
        <p className="text-sm text-muted">
          If an account exists for <b className="text-ink">{email.trim()}</b>, we've sent a temporary password from connect@mcreatik.com.
          It works for 30 minutes. Check your spam folder too.
        </p>
        <button type="button" onClick={onBack} className="h-11 w-full rounded-xl bg-ink text-sm font-medium text-white">Back to sign in</button>
      </div>
    )
  }

  return (
    <form onSubmit={submit} className="space-y-4" aria-label="Forgot password">
      <p className="text-sm text-muted">Enter your login email. We'll send you a temporary password to sign in and choose a new one.</p>
      <label className="block text-sm font-medium">Email
        <input type="email" required autoComplete="username" value={email} onChange={(e) => setEmail(e.target.value)}
          className="mt-1 h-11 w-full rounded-xl border border-line bg-white px-3 text-base outline-none focus:border-ink" />
      </label>
      {error && <p className="text-sm text-red-700" role="alert">{error}</p>}
      <button type="submit" disabled={busy} className="h-11 w-full rounded-xl bg-ink text-sm font-medium text-white disabled:opacity-60">
        {busy ? 'Sending…' : 'Send temporary password'}
      </button>
      <button type="button" onClick={onBack} className="w-full text-center text-sm text-muted underline underline-offset-2 hover:text-ink">Back to sign in</button>
    </form>
  )
}
