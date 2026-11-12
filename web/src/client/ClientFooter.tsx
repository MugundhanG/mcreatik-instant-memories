import { BUSINESS } from '../shared/policies/business'
import { formatPhone, telLink, waLink } from '../shared/phone'
import type { ResolvedBrand } from '../shared/brand'
import { InstagramIcon, InstagramLink } from '../shared/StudioLogo'

/**
 * The client gallery's footer: the studio's own contact details (empty ones left out) and the copyright line. No
 * Pricing, Terms, Refunds or Privacy links here: those belong to the studio's account, not the couple's gallery.
 */
export function ClientFooter({ brand, className = '' }: { brand: ResolvedBrand; className?: string }) {
  const phone = brand.phone
  const link = 'inline-flex items-center gap-1.5 hover:text-ink hover:underline'
  return (
    <footer className={`no-print px-4 text-center text-xs text-muted ${className}`} data-testid="client-footer">
      {brand.name && <p className="text-sm font-medium text-ink">{brand.name}</p>}
      {(phone || brand.instagram) && (
        <p className="mt-2 flex flex-wrap items-center justify-center gap-x-4 gap-y-1.5">
          {phone && (
            <>
              <a href={waLink(phone, `Hi ${brand.name}, about our photos`)} target="_blank" rel="noopener noreferrer" className={link}>WhatsApp {formatPhone(phone)}</a>
              <a href={telLink(phone)} className={link}>Call</a>
            </>
          )}
          {brand.instagram && (
            <InstagramLink handle={brand.instagram} className={link}>
              <InstagramIcon className="size-3.5" />@{brand.instagram}
            </InstagramLink>
          )}
        </p>
      )}
      <p className="mt-3">© {new Date().getFullYear()} {BUSINESS.name} · <a href={`mailto:${BUSINESS.email}`} className="hover:text-ink hover:underline">{BUSINESS.email}</a></p>
    </footer>
  )
}
