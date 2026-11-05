import { useEffect, useState } from 'react'
import type { Brand } from '../../lib/types'
import { resolveBrand } from '../../shared/brand'
import {
  dataUrl, embeddedFonts, imageDataUrl, isBoardTheme,
  type BoardBrand, type BoardTheme, type EmbeddedFonts,
} from '../../shared/printDesign'

export function themeOf(brand: Brand | undefined): BoardTheme {
  return isBoardTheme(brand?.boardTheme) ? brand.boardTheme : 'cream'
}

/**
 * The studio's logo as a data: URL. Through the API first: the browser may only draw an image from another site
 * (the public media domain) into a downloadable file when that site allows it, and the API always does.
 */
async function loadLogo(url: string | null, viaApi: (() => Promise<Blob>) | undefined): Promise<string | null> {
  if (!url) return null
  if (viaApi) {
    try {
      return await dataUrl(await viaApi())
    } catch {
      // Fall back to the public URL below.
    }
  }
  return imageDataUrl(url)
}

/**
 * Fonts and logo the print files embed. `logoMissing`: the studio has a logo, but it could not be loaded.
 * `logoFromApi` loads the studio's logo file through the API (McreatiK's own logo is part of the site).
 */
export function usePrintAssets(brand: Brand | undefined, logoFromApi?: () => Promise<Blob>) {
  const resolved = resolveBrand(brand)
  const logoUrl = resolved.posterLogo
  const [state, setState] = useState<{ fonts: EmbeddedFonts; logo: string | null; logoUrl: string | null } | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    const studioLogo = brand && !brand.mcreatik && !!brand.logoUrl
    Promise.all([embeddedFonts(), loadLogo(logoUrl, studioLogo ? logoFromApi : undefined)])
      .then(([fonts, logo]) => {
        if (!cancelled) setState({ fonts, logo, logoUrl })
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- reload only when the logo itself changes
  }, [logoUrl])

  const ready = state !== null && state.logoUrl === logoUrl
  const boardBrand: BoardBrand = {
    name: resolved.name,
    logo: ready ? state.logo : null,
    logoScale: resolved.posterLogoScale,
    instagram: resolved.instagram,
  }
  return {
    ready,
    error,
    fonts: ready ? state.fonts : null,
    boardBrand,
    logoMissing: ready && !!logoUrl && !state.logo,
  }
}
