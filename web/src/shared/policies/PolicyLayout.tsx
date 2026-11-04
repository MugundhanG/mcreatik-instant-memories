import type { ReactNode } from 'react'
import { BUSINESS, POLICY_LINKS } from './business'

export function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="mt-8">
      <h2 className="text-lg font-semibold text-ink">{title}</h2>
      <div className="mt-2 space-y-2 text-[15px] leading-relaxed text-ink/85">{children}</div>
    </section>
  )
}

/** Small links row to every policy page: for footers (dashboard, login, Buy events). */
export function PolicyLinks({ className = '' }: { className?: string }) {
  return (
    <nav aria-label="Policies" className={`flex flex-wrap justify-center gap-x-3 gap-y-1 text-xs text-muted ${className}`}>
      {POLICY_LINKS.map((l) => (
        <a key={l.to} href={l.to} className="underline-offset-2 hover:text-ink hover:underline">{l.label}</a>
      ))}
    </nav>
  )
}

/** The plain page frame shared by /terms, /refunds, /pricing and /contact. */
export function PolicyLayout({ title, updated, children }: { title: string; updated: string; children: ReactNode }) {
  return (
    <div className="min-h-dvh bg-paper">
      <main className="mx-auto max-w-2xl px-5 py-12">
        <p className="text-xs font-semibold tracking-[0.3em] text-[#8a5a1c] uppercase">{BUSINESS.product}</p>
        <h1 className="mt-2 font-display text-4xl font-medium text-ink">{title}</h1>
        <p className="mt-2 text-sm text-muted">Last updated {updated}</p>
        {children}
        <footer className="mt-12 border-t border-line pt-6">
          <PolicyLinks />
          <p className="mt-3 text-center text-xs text-muted">© {BUSINESS.name} · {BUSINESS.email}</p>
        </footer>
      </main>
    </div>
  )
}
