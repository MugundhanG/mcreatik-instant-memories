import logoUrl from '../assets/mcreatik-studios-logo.webp'

export const STUDIO_NAME = 'McreatiK Studios'

/** McreatiK Studios logo (transparent, 400 px wide source; shown at 2x or less for sharp edges). */
export function StudioLogo({ className = '' }: { className?: string }) {
  return <img src={logoUrl} alt="" aria-hidden="true" width={400} height={307} className={`w-auto ${className}`} />
}

/** Logo with the studio name beside it, for page headers. */
export function StudioBrand({ className = '', logoClassName, nameClassName }: { className?: string; logoClassName: string; nameClassName: string }) {
  return (
    <div className={`flex items-center gap-3 ${className}`}>
      <StudioLogo className={logoClassName} />
      <span className={`font-display font-medium text-ink ${nameClassName}`}>{STUDIO_NAME}</span>
    </div>
  )
}
