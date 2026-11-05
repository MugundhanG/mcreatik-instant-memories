import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import { adminApi, ApiError } from '../lib/api'
import type { BillingSummary, EventStatus, EventType, Studio } from '../lib/types'
import { useSession } from './auth'
import { BuyEventsDialog } from './BuyEventsDialog'
import { rupees } from './plans'
import { Button, Card, ErrorText, Field, inputClass } from './ui'

/** How this event will be paid for: the pack, a bought event, or a price to pay. */
function coverage(b: BillingSummary, type: EventType): string {
  if (b.free) return 'Included in your plan.'
  const packCovers = b.packActive && (b.plan === 'MONTHLY' || b.plan.startsWith('MAIN') || (type === 'QUICK' && b.plan.startsWith('QUICK')))
  if (packCovers && b.carryOver > 0) return `Uses 1 of the ${b.carryOver} event${b.carryOver === 1 ? '' : 's'} carried over from last month.`
  if (packCovers && b.usedThisMonth < b.monthlyQuota) return `Included in your pack: ${b.monthlyQuota - b.usedThisMonth} of ${b.monthlyQuota} events left this month.`
  if (type === 'QUICK' && b.quickCredits > 0) return `Uses 1 of your ${b.quickCredits} bought Quick event${b.quickCredits === 1 ? '' : 's'}.`
  if (b.mainCredits > 0) return `Uses 1 of your ${b.mainCredits} bought Main event${b.mainCredits === 1 ? '' : 's'}.`
  return `You'll be asked to pay ${rupees(type === 'QUICK' ? b.prices.quick : b.unitPrice)} for this event.`
}

export default function NewEventPage() {
  const navigate = useNavigate()
  const [name, setName] = useState('')
  const [eventDate, setEventDate] = useState(() => new Date().toISOString().slice(0, 10))
  const [status, setStatus] = useState<EventStatus>('UPCOMING')
  const [slug, setSlug] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [eventType, setEventType] = useState<EventType>('MAIN')
  const [billing, setBilling] = useState<BillingSummary | null>(null)
  const [payment, setPayment] = useState<{ billing: BillingSummary; reason: string } | null>(null)
  // The super admin can create the event for a client studio.
  const superAdmin = useSession()?.role === 'SUPER_ADMIN'
  const [studios, setStudios] = useState<Studio[]>([])
  const [ownerId, setOwnerId] = useState('')
  useEffect(() => {
    if (superAdmin) adminApi.studios().then((list) => setStudios(list.filter((s) => !s.disabled))).catch(() => {})
  }, [superAdmin])
  useEffect(() => {
    adminApi.billing().then(setBilling).catch(() => {})
  }, [])

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    await create()
  }

  const create = async () => {
    try {
      const event = await adminApi.createEvent({ name, eventDate, status, slug: slug.trim() || undefined, ownerId: ownerId || undefined, eventType })
      navigate(`/admin/events/${event.id}`, { replace: true })
    } catch (err) {
      // Out of events for this month (or a per-event plan): offer to buy, then create it straight after.
      if (err instanceof ApiError && err.code === 'EVENT_PAYMENT_REQUIRED' && !ownerId) {
        try {
          setPayment({ billing: await adminApi.billing(), reason: err.message })
        } catch {
          setError(err.message)
        }
      } else {
        setError(err instanceof ApiError ? err.message : 'Could not create the event')
      }
      setBusy(false)
    }
  }

  return (
    <div className="mx-auto max-w-xl space-y-4">
      {payment && (
        <BuyEventsDialog
          billing={payment.billing}
          purchase={{ item: eventType === 'QUICK' ? 'QUICK_EVENT' : 'MAIN_EVENT', quantity: 1 }}
          reason={payment.reason}
          onClose={() => setPayment(null)}
          onPaid={() => {
            setPayment(null)
            setBusy(true)
            void create()
          }}
        />
      )}
      <Link to="/admin" className="text-sm text-muted hover:text-ink">← Events</Link>
      <Card title="New event">
        <form onSubmit={submit} className="space-y-5">
          {superAdmin && studios.length > 1 && (
            <Field label="Studio" hint="Whose event this is: they see it in their own dashboard">
              <select className={inputClass} value={ownerId} onChange={(e) => setOwnerId(e.target.value)}>
                {studios.map((s) => (
                  <option key={s.id} value={s.role === 'SUPER_ADMIN' ? '' : s.id}>{s.role === 'SUPER_ADMIN' ? `${s.name} (my own event)` : s.name}</option>
                ))}
              </select>
            </Field>
          )}
          <fieldset>
            <legend className="mb-2 text-sm font-medium">Event type</legend>
            <div role="radiogroup" aria-label="Event type" className="grid gap-2 sm:grid-cols-2">
              {([
                ['MAIN', 'Main event', 'Weddings, receptions, big birthdays, big functions and corporate events.', billing?.unitPrice],
                ['QUICK', 'Quick event', `Engagements, small birthdays, baby showers: up to about ${billing?.prices.upgradeGuests ?? 300} guests. Upgrade to Main later if it grows.`, billing?.prices.quick],
              ] as const).map(([type, title, body, price]) => (
                <button key={type} type="button" role="radio" aria-checked={eventType === type} onClick={() => setEventType(type)}
                  className={`rounded-xl border p-3 text-left transition-colors ${eventType === type ? 'border-ink bg-stone-50 ring-1 ring-ink' : 'border-line hover:border-stone-400'}`}>
                  <span className="flex items-baseline justify-between gap-2">
                    <span className="font-medium">{title}</span>
                    {price !== undefined && billing && !billing.free && <span className="text-sm tabular-nums text-muted">{rupees(price)}</span>}
                  </span>
                  <span className="mt-1 block text-xs text-muted">{body}</span>
                </button>
              ))}
            </div>
            {billing && !ownerId && <p className="mt-2 text-xs text-muted" data-testid="coverage">{coverage(billing, eventType)}</p>}
          </fieldset>
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
          <Field label="Gallery address (optional)" hint="Leave empty for a hard-to-guess address like arun-priya-7k3dqx9mzp. A custom address is easier for strangers to guess.">
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
