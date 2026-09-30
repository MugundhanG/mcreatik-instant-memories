import type { ButtonHTMLAttributes, ReactNode } from 'react'
import type { EventStatus, UploaderStatus } from '../lib/types'

type Variant = 'primary' | 'secondary' | 'danger' | 'ghost'

const variants: Record<Variant, string> = {
  primary: 'bg-ink text-white hover:bg-stone-800',
  secondary: 'border border-line bg-white text-ink hover:bg-stone-50',
  danger: 'border border-red-200 bg-white text-red-700 hover:bg-red-50',
  ghost: 'text-ink hover:bg-stone-100',
}

export function Button({ variant = 'secondary', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex h-9 items-center justify-center gap-2 rounded-lg px-3.5 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${variants[variant]} ${className}`}
    />
  )
}

export function Card({ title, action, children, className = '' }: { title?: string; action?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-xl border border-line bg-white ${className}`}>
      {(title || action) && (
        <div className="flex items-center justify-between gap-4 border-b border-line px-5 py-3.5">
          {title && <h2 className="text-sm font-semibold">{title}</h2>}
          {action}
        </div>
      )}
      <div className="p-5">{children}</div>
    </section>
  )
}

const eventStatusStyles: Record<EventStatus, string> = {
  DRAFT: 'bg-stone-100 text-stone-600',
  UPCOMING: 'bg-sky-50 text-sky-700',
  LIVE: 'bg-red-50 text-red-700',
  COMPLETED: 'bg-emerald-50 text-emerald-700',
  ARCHIVED: 'bg-stone-100 text-stone-500',
}

export function EventStatusBadge({ status }: { status: EventStatus }) {
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium ${eventStatusStyles[status]}`}>
      {status === 'LIVE' && <span className="live-dot size-1.5 rounded-full bg-red-600" aria-hidden="true" />}
      {status.charAt(0) + status.slice(1).toLowerCase()}
    </span>
  )
}

const uploaderDot: Record<UploaderStatus, string> = {
  ONLINE: 'bg-emerald-500',
  OFFLINE: 'bg-stone-300',
  ERROR: 'bg-red-500',
}

export function UploaderStatusDot({ status }: { status: UploaderStatus }) {
  return <span className={`inline-block size-2.5 rounded-full ${uploaderDot[status]}`} aria-hidden="true" />
}

export function Stat({ label, value, tone }: { label: string; value: ReactNode; tone?: 'danger' }) {
  return (
    <div className="rounded-xl border border-line bg-white px-4 py-3.5">
      <div className={`text-2xl font-semibold tabular-nums ${tone === 'danger' ? 'text-red-700' : ''}`}>{value}</div>
      <div className="mt-0.5 text-xs text-muted">{label}</div>
    </div>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label className="block">
      <span className="text-sm font-medium">{label}</span>
      <div className="mt-1.5">{children}</div>
      {hint && <span className="mt-1 block text-xs text-muted">{hint}</span>}
    </label>
  )
}

export const inputClass =
  'block h-10 w-full rounded-lg border border-line bg-white px-3 text-sm outline-none focus:border-ink focus:ring-1 focus:ring-ink'

export function ErrorText({ children }: { children: ReactNode }) {
  if (!children) return null
  return <p role="alert" className="text-sm text-red-700">{children}</p>
}
