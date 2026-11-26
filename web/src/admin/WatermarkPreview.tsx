import { useEffect, useRef } from 'react'
import type { MarkSettings } from '../lib/types'
import { drawMark, markedSize, prepareLogo } from '../lib/watermark'
import type { LogoArt } from '../lib/watermark'

const W = 1200
const H = 800

/** A neutral generated sample photo (soft evening sky, hills, two figures), so the mark is judged on a real-ish image. */
function drawSamplePhoto(ctx: CanvasRenderingContext2D, width: number, height: number) {
  const sky = ctx.createLinearGradient(0, 0, 0, height)
  sky.addColorStop(0, '#6f8fb5')
  sky.addColorStop(0.55, '#e9b99a')
  sky.addColorStop(1, '#f6dcc0')
  ctx.fillStyle = sky
  ctx.fillRect(0, 0, width, height)
  ctx.fillStyle = 'rgba(255,244,214,.9)'
  ctx.beginPath()
  ctx.arc(width * 0.72, height * 0.32, height * 0.09, 0, Math.PI * 2)
  ctx.fill()
  ctx.fillStyle = '#5d6b57'
  ctx.beginPath()
  ctx.moveTo(0, height * 0.72)
  ctx.quadraticCurveTo(width * 0.3, height * 0.55, width * 0.6, height * 0.72)
  ctx.quadraticCurveTo(width * 0.85, height * 0.82, width, height * 0.66)
  ctx.lineTo(width, height)
  ctx.lineTo(0, height)
  ctx.fill()
  ctx.fillStyle = '#3c4a3a'
  ctx.fillRect(0, height * 0.86, width, height * 0.14)
  ctx.fillStyle = '#2b2623'
  for (const [x, w, h] of [[0.42, 0.045, 0.3], [0.5, 0.05, 0.27]] as const) {
    ctx.beginPath()
    ctx.ellipse(width * x, height * (0.86 - h) + height * 0.04, width * w * 0.45, height * 0.05, 0, 0, Math.PI * 2)
    ctx.fill()
    ctx.fillRect(width * (x - w / 2), height * (0.86 - h) + height * 0.06, width * w, height * h * 0.85)
  }
}

/** Loads the studio's logo: through the API when given (CORS-safe), else straight from its public URL. */
async function loadLogoArt(logoUrl: string, viaApi?: () => Promise<Blob>): Promise<LogoArt | null> {
  try {
    if (viaApi) return prepareLogo(await createImageBitmap(await viaApi()))
  } catch {
    // Try the public URL.
  }
  try {
    const img = new Image()
    img.crossOrigin = 'anonymous'
    img.src = logoUrl
    await img.decode()
    return prepareLogo(Object.assign(img, { width: img.naturalWidth, height: img.naturalHeight }))
  } catch {
    return null
  }
}

/**
 * What guests' downloads will look like: the real mark drawing (lib/watermark drawMark) on a generated sample photo.
 * Pass the logo only when the studio has one.
 */
export function WatermarkPreview({ settings, name, instagram, phone, logoUrl, logoScale, loadLogo }: {
  settings: MarkSettings
  name: string
  instagram: string | null
  phone: string | null
  logoUrl: string | null
  logoScale: number
  loadLogo?: () => Promise<Blob>
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const logo = useRef<{ url: string; art: LogoArt | null } | null>(null)

  useEffect(() => {
    let cancelled = false
    const paint = (art: LogoArt | null) => {
      const el = canvas.current
      const ctx = el?.getContext('2d')
      if (!el || !ctx) return
      const size = markedSize(W, H, settings)
      el.width = size.width
      el.height = size.height
      ctx.clearRect(0, 0, size.width, size.height)
      drawSamplePhoto(ctx, W, H)
      drawMark(ctx, W, H, { logo: art, name, instagram, phone, logoScale, settings })
    }
    if (!logoUrl) {
      paint(null)
    } else if (logo.current?.url === logoUrl) {
      paint(logo.current.art)
    } else {
      paint(null)
      void loadLogoArt(logoUrl, loadLogo).then((art) => {
        logo.current = { url: logoUrl, art }
        if (!cancelled) paint(art)
      })
    }
    return () => {
      cancelled = true
    }
    // loadLogo is a stable closure of the same account; reload only when the logo itself changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [settings, name, instagram, phone, logoUrl, logoScale])

  return (
    <canvas ref={canvas} width={W} height={H} role="img" aria-label="Preview of the watermark on a sample photo" data-testid="watermark-preview"
      className="block h-auto w-full max-w-sm rounded-xl border border-line" />
  )
}
