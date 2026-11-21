import { useMemo } from 'react'
import { API_BASE_URL } from './api'
import type { PublicEvent } from './types'

/*
 * The studio's mark on photos guests download and share: its logo (or, without one, its name) on a soft white label
 * in the bottom-right corner, so any logo reads on any photo. The server draws the same label on its plain download
 * link (backend Watermark.java); keep these numbers in step with it.
 */
export const LOGO_HEIGHT = 0.07
export const MAX_WIDTH = 0.38
export const MARGIN = 0.03
export const PADDING = 0.012
export const LABEL_ALPHA = 0.78
const JPEG_QUALITY = 0.92
const TEXT_COLOR = '#2a2520'

/** Turns a downloaded photo into the one a guest saves or shares. */
export type Watermarker = (photo: Blob) => Promise<Blob>

export interface Mark {
  logo: CanvasImageSource & { width: number; height: number } | null
  name: string
  /** The studio's logo size setting, 50–200 %. */
  logoScale: number
}

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n))

/** Draws the label onto a {@code width} x {@code height} canvas. */
export function drawMark(ctx: CanvasRenderingContext2D, width: number, height: number, mark: Mark) {
  const shortSide = Math.min(width, height)
  const scale = clamp(mark.logoScale || 100, 50, 200) / 100
  const pad = Math.max(4, Math.round(shortSide * PADDING))
  const margin = Math.max(6, Math.round(shortSide * MARGIN))
  const maxContentW = Math.floor(width * MAX_WIDTH) - 2 * pad
  let contentH = Math.max(12, Math.round(shortSide * LOGO_HEIGHT * scale))
  let contentW: number
  let fontSize = 0
  if (mark.logo) {
    contentW = Math.round((contentH * mark.logo.width) / mark.logo.height)
    if (contentW > maxContentW) {
      contentH = Math.round((contentH * maxContentW) / contentW)
      contentW = maxContentW
    }
  } else {
    fontSize = Math.max(10, Math.round(contentH * 0.55))
    const font = () => `700 ${fontSize}px "Inter Variable", system-ui, sans-serif`
    ctx.font = font()
    while (ctx.measureText(mark.name).width > maxContentW && fontSize > 10) {
      fontSize--
      ctx.font = font()
    }
    contentW = Math.min(maxContentW, Math.ceil(ctx.measureText(mark.name).width))
    contentH = Math.round(fontSize * 1.25)
  }
  const boxW = contentW + 2 * pad
  const boxH = contentH + 2 * pad
  const x = width - margin - boxW
  const y = height - margin - boxH
  ctx.save()
  ctx.globalAlpha = LABEL_ALPHA
  ctx.fillStyle = '#fff'
  ctx.beginPath()
  if (ctx.roundRect) ctx.roundRect(x, y, boxW, boxH, pad)
  else ctx.rect(x, y, boxW, boxH)
  ctx.fill()
  ctx.globalAlpha = 1
  if (mark.logo) {
    ctx.drawImage(mark.logo, x + pad, y + pad, contentW, contentH)
  } else {
    ctx.fillStyle = TEXT_COLOR
    ctx.textBaseline = 'middle'
    ctx.fillText(mark.name, x + pad, y + boxH / 2, maxContentW)
  }
  ctx.restore()
}

async function decode(blob: Blob): Promise<ImageBitmap> {
  return createImageBitmap(blob)
}

/** The photo with the mark, as a JPEG. */
export async function markPhoto(photo: Blob, mark: Mark): Promise<Blob> {
  const img = await decode(photo)
  const canvas = document.createElement('canvas')
  try {
    canvas.width = img.width
    canvas.height = img.height
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('No canvas')
    ctx.drawImage(img, 0, 0)
    drawMark(ctx, canvas.width, canvas.height, mark)
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not make the photo'))), 'image/jpeg', JPEG_QUALITY))
  } finally {
    img.close?.()
    canvas.width = 0
    canvas.height = 0
  }
}

/** The owner's logo through the API (a canvas can only read images served with CORS); null when it has none. */
async function loadLogo(slug: string): Promise<ImageBitmap | null> {
  try {
    const res = await fetch(`${API_BASE_URL}/api/public/events/${encodeURIComponent(slug)}/logo`, { mode: 'cors', credentials: 'omit' })
    if (!res.ok) return null
    return await decode(await res.blob())
  } catch {
    return null
  }
}

/**
 * The watermarker for an event's guest gallery, or undefined when its watermark is off (photos stay as they are).
 * The logo is fetched once, on the first photo that needs it.
 */
export function useWatermarker(event: Pick<PublicEvent, 'slug' | 'watermark' | 'brand' | 'name'>): Watermarker | undefined {
  const { slug, watermark, brand } = event
  const name = brand?.name ?? 'McreatiK Studios'
  const logoUrl = brand?.logoUrl ?? null
  const logoScale = brand?.logoScale ?? 100
  return useMemo(() => {
    if (!watermark) return undefined
    let logo: Promise<ImageBitmap | null> | null = null
    return async (photo: Blob) => {
      logo ??= logoUrl ? loadLogo(slug) : Promise.resolve(null)
      return markPhoto(photo, { logo: await logo, name, logoScale })
    }
    // logoUrl changes when the studio uploads a new logo: fetch that one.
  }, [watermark, slug, name, logoUrl, logoScale])
}
