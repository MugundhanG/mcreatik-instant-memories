/** McreatiK Studios logo (transparent, 400 px wide source; shown at 2x or less for sharp edges). */
export function StudioLogo({ className = '' }: { className?: string }) {
  return <img src="/mcreatik-studios-logo.webp" alt="McreatiK Studios" width={400} height={307} className={`w-auto ${className}`} />
}
