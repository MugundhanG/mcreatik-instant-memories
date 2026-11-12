import { useState } from 'react'
import { adminApi, ApiError } from '../lib/api'
import { formatBytes, formatCount, formatEventDate } from '../lib/format'
import type { Studio, StudioBilling } from '../lib/types'
import { StudioPlan } from './BillingAdmin'
import { BrandingCard } from './BrandingCard'
import { PLAN_LABEL } from './plans'
import { SendButtons } from './SendButtons'
import { temporaryPassword } from './sendLinks'
import { loginMessage, outgoing, type Credentials } from './studioLogin'
import { Button } from './ui'
import type { useConfirm } from './useConfirm'

const chevron = (open: boolean) => (
  <svg viewBox="0 0 16 16" className={`size-4 shrink-0 text-muted transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true">
    <path d="M3.5 6l4.5 4.5L12.5 6" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
  </svg>
)

/**
 * One studio, collapsed to a row (name, email, plan, status, events) that opens to its usage, plan, actions and
 * branding. Everything about a studio (a new temporary password included) shows inside its own item.
 */
export function StudioAccordionItem({ studio: s, billing, open, onToggle, confirm, reload, onError }: {
  studio: Studio
  billing?: StudioBilling
  open: boolean
  onToggle: () => void
  confirm: ReturnType<typeof useConfirm>[0]
  reload: () => void
  onError: (message: string | null) => void
}) {
  const [fresh, setFresh] = useState<Credentials | null>(null)
  const [editingBranding, setEditingBranding] = useState(false)
  const status = s.disabled
    ? <span className="rounded bg-red-50 px-1.5 py-0.5 text-xs text-red-700">Disabled</span>
    : s.mustChangePassword
      ? <span className="rounded bg-amber-50 px-1.5 py-0.5 text-xs text-amber-800">Not logged in yet</span>
      : <span className="rounded bg-emerald-50 px-1.5 py-0.5 text-xs text-emerald-700">Active</span>

  const resetPassword = async () => {
    const ok = await confirm({
      title: `New password for ${s.name}?`,
      body: 'Their current password stops working. You get a new temporary password to send them; they choose their own at the next login.',
      confirmLabel: 'Create new password',
    })
    if (!ok) return
    const password = temporaryPassword()
    try {
      await adminApi.updateStudio(s.id, { newPassword: password })
      setFresh({ name: s.name, email: s.email, password })
      reload()
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'Could not reset the password')
    }
  }

  const toggleDisabled = async () => {
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
    reload()
  }

  const deleteStudio = async () => {
    const ok = await confirm({
      title: `Delete ${s.name} for good?`,
      body: `Their login, all ${formatCount(s.events)} events with ${formatCount(s.photos)} photos, guest and couple galleries, and their payment history are deleted. This cannot be undone. To only stop them logging in, use Disable instead.`,
      confirmLabel: 'Delete studio',
      danger: true,
      requireText: s.name,
    })
    if (!ok) return
    try {
      await adminApi.deleteStudio(s.id)
    } catch (e) {
      onError(e instanceof ApiError ? e.message : 'Could not delete the studio')
    }
    reload()
  }

  return (
    <li data-testid={`studio-${s.id}`}>
      <button type="button" onClick={onToggle} aria-expanded={open} aria-controls={`studio-panel-${s.id}`}
        className="flex w-full items-center gap-3 py-3 text-left hover:bg-stone-50 sm:px-2 sm:rounded-lg">
        <span className="min-w-0 flex-1">
          <span className="flex flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-medium">{s.name}</span>
            {billing && <span className="rounded bg-stone-100 px-1.5 py-0.5 text-xs text-stone-700">{PLAN_LABEL[billing.plan]}</span>}
            {status}
          </span>
          <span className="mt-0.5 block truncate text-xs text-muted">{s.email}</span>
        </span>
        <span className="shrink-0 text-right text-sm tabular-nums"><b>{formatCount(s.events)}</b> <span className="text-muted">{s.events === 1 ? 'event' : 'events'}</span></span>
        {chevron(open)}
      </button>
      {open && (
        <div id={`studio-panel-${s.id}`} className="space-y-4 pb-4 sm:px-2">
          <dl className="grid grid-cols-3 gap-2 text-sm tabular-nums">
            {[['Events', formatCount(s.events)], ['Photos', formatCount(s.photos)], ['Stored', formatBytes(s.storageBytes)]].map(([k, v]) => (
              <div key={k} className="rounded-lg bg-stone-50 px-3 py-2"><dd className="font-semibold">{v}</dd><dt className="text-xs text-muted">{k}</dt></div>
            ))}
          </dl>
          <p className="text-xs text-muted">Studio since {formatEventDate(s.createdAt.slice(0, 10))}</p>
          {billing && <StudioPlan key={JSON.stringify(billing)} billing={billing} onChange={reload} />}
          <div className="flex flex-wrap gap-2">
            <Button onClick={() => setEditingBranding((v) => !v)} aria-expanded={editingBranding}>Edit branding</Button>
            <Button onClick={() => void resetPassword()}>Reset password</Button>
            <Button variant={s.disabled ? 'secondary' : 'danger'} onClick={() => void toggleDisabled()}>{s.disabled ? 'Enable' : 'Disable'}</Button>
            <Button variant="danger" onClick={() => void deleteStudio()}>Delete</Button>
          </div>
          {fresh && (
            <div className="rounded-xl border border-emerald-200 p-4">
              <p className="text-sm font-medium">New password for {fresh.name}</p>
              <p className="text-sm text-muted">Send this to the studio. The password is shown only now.</p>
              <pre className="mt-3 overflow-x-auto rounded-lg bg-stone-50 p-3 text-sm whitespace-pre-wrap" data-testid="studio-credentials">{loginMessage(fresh)}</pre>
              <SendButtons outgoing={outgoing(fresh)} onDone={() => setFresh(null)} />
            </div>
          )}
          {editingBranding && s.brand && (
            <div className="rounded-xl border border-line p-4">
              <BrandingCard embedded brand={s.brand} onCancel={() => setEditingBranding(false)} onSaved={() => reload()}
                actions={{
                  update: (input) => adminApi.updateStudioBranding(s.id, input),
                  upload: (png) => adminApi.uploadStudioLogo(s.id, png),
                  remove: () => adminApi.removeStudioLogo(s.id),
                }} />
            </div>
          )}
        </div>
      )}
    </li>
  )
}
