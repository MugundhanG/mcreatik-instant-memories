import { BUSINESS } from './policies/business'
import { PolicyLinks } from './policies/PolicyLayout'

/** Policy links plus the copyright line with the contact email: the footer of every page outside the guest gallery. */
export function SiteFooter({ className = '' }: { className?: string }) {
  return (
    <footer className={`no-print ${className}`}>
      <PolicyLinks />
      <p className="mt-2 text-center text-xs text-muted">© {new Date().getFullYear()} {BUSINESS.name} · <a href={`mailto:${BUSINESS.email}`} className="hover:text-ink hover:underline">{BUSINESS.email}</a></p>
    </footer>
  )
}
