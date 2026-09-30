import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import type { EventStatus } from '../lib/types'
import { Button, Card, ErrorText, Field, inputClass } from './ui'

export default function NewEventPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [eventDate, setEventDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [status, setStatus] = useState<EventStatus>('UPCOMING')
  const [slug, setSlug] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const event = await adminApi.createEvent({ name, eventDate, status, slug: slug.trim() || undefined })
      navigate(`/admin/events/${event.id}`, { replace: true })
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create the event')
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-4">
      <Link to="/admin" className="text-sm text-muted hover:text-ink">← Events</Link>
      <Card title="New event">
        <form onSubmit={submit} className="space-y-5">
          <Field label="Event name" hint="Shown to guests, e.g. “Arun & Priya Wedding”">
            <input className={inputClass} required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
          </Field>
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label="Date">
              <input className={inputClass} type="date" required value={eventDate} onChange={(e) => setEventDate(e.target.value)} />
            </Field>
            <Field label="Status">
              <select className={inputClass} value={status} onChange={(e) => setStatus(e.target.value as EventStatus)}>
                <option value="DRAFT">Draft (gallery hidden)</option>
                <option value="UPCOMING">Upcoming</option>
                <option value="LIVE">Live</option>
              </select>
            </Field>
          </div>
          <Field label="Gallery address (optional)" hint="Leave empty to generate a hard-to-guess address like arun-priya-7k3d">
            <input className={inputClass} value={slug} placeholder="auto" onChange={(e) => setSlug(e.target.value.toLowerCase())} />
          </Field>
          <ErrorText>{error}</ErrorText>
          <div className="flex justify-end gap-2">
            <Button onClick={() => navigate('/admin')}>Cancel</Button>
            <Button type="submit" variant="primary" disabled={busy}>{busy ? 'Creating…' : 'Create event'}</Button>
          </div>
        </form>
      </Card>
    </div>
  )
}
