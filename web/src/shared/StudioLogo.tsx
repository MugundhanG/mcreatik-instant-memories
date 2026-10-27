import type React from 'react'
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

export const INSTAGRAM_HANDLE = 'mcreatik.studios'
export const INSTAGRAM_URL = `https://www.instagram.com/${INSTAGRAM_HANDLE}/`

export function InstagramIcon({ className = 'size-4' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" className={className} fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden="true">
      <rect x="3" y="3" width="18" height="18" rx="5" />
      <circle cx="12" cy="12" r="4.2" />
      <circle cx="17.4" cy="6.6" r="1.1" fill="currentColor" stroke="none" />
    </svg>
  )
}

/** Opens the studio's Instagram profile (the Instagram app when installed). */
export function InstagramLink({ className = '', children }: { className?: string; children: React.ReactNode }) {
  return (
    <a href={INSTAGRAM_URL} target="_blank" rel="noopener noreferrer" className={className}>
      {children}
    </a>
  )
}
