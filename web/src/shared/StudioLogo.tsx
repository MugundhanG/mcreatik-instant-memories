import logoUrl from '../assets/mcreatik-studios-logo.webp'

export const STUDIO_NAME = 'McreatiK Studios'

/** McreatiK Studios logo (transparent, 400 px wide source; shown at 2x or less for sharp edges). */
export function StudioLogo({ className = '' }: { className?: string }) {
  return <img src={logoUrl} alt="" aria-hidden="true" width={400} height={307} className={`w-auto ${className}`} />
}

/** The studio name as a heading line above the event name. */
export function StudioName({ className = '' }: { className?: string }) {
  return <p className={`font-display font-medium tracking-wide text-ink ${className}`}>{STUDIO_NAME}</p>
}
