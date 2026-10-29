import logoLightUrl from '../assets/mcreatik-studios-logo-light.webp'
import markUrl from '../assets/mcreatik-mark.webp'
import type { Brand } from '../lib/types'
import { INSTAGRAM_HANDLE, STUDIO_NAME } from './StudioLogo'

/** What a gallery or poster shows for an event's brand. */
export interface ResolvedBrand {
  name: string
  instagram: string | null
  /** Logo straight on the dark cover photo (McreatiK's light logo). */
  heroLogo: string | null
  /** Studio logo of unknown colours: shown on a light card so it is always readable. */
  heroLogoOnCard: boolean
  posterLogo: string | null
  /** Client studios' galleries credit McreatiK, which runs the service. */
  poweredByMcreatik: boolean
}

export const MCREATIK_BRAND: ResolvedBrand = {
  name: STUDIO_NAME,
  instagram: INSTAGRAM_HANDLE,
  heroLogo: logoLightUrl,
  heroLogoOnCard: false,
  posterLogo: markUrl,
  poweredByMcreatik: false,
}

export function resolveBrand(brand: Brand | undefined): ResolvedBrand {
  if (!brand || brand.mcreatik) return MCREATIK_BRAND
  return {
    name: brand.name,
    instagram: brand.instagram,
    heroLogo: brand.logoUrl,
    heroLogoOnCard: true,
    posterLogo: brand.logoUrl,
    poweredByMcreatik: true,
  }
}
