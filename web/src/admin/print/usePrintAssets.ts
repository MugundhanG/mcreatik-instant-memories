import { useEffect, useState } from 'react'
import type { Brand } from '../../lib/types'
import { resolveBrand } from '../../shared/brand'
import {
  embeddedFonts, imageDataUrl, isBoardTheme,
  type BoardBrand, type BoardTheme, type EmbeddedFonts,
} from '../../shared/printDesign'

export function themeOf(brand: Brand | undefined): BoardTheme {
  return isBoardTheme(brand?.boardTheme) ? brand.boardTheme : 'cream'
}

/** Fonts and logo the print files embed. `logoMissing`: the studio has a logo, but it could not be loaded. */
export function usePrintAssets(brand: Brand | undefined) {
  const resolved = resolveBrand(brand)
  const logoUrl = resolved.posterLogo
  const [state, setState] = useState<{ fonts: EmbeddedFonts; logo: string | null; logoUrl: string | null } | null>(null)
  const [error, setError] = useState(false)

  useEffect(() => {
    let cancelled = false
    Promise.all([embeddedFonts(), imageDataUrl(logoUrl)])
      .then(([fonts, logo]) => {
        if (!cancelled) setState({ fonts, logo, logoUrl })
      })
      .catch(() => {
        if (!cancelled) setError(true)
      })
    return () => {
      cancelled = true
    }
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
