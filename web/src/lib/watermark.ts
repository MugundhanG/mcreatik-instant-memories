import { useMemo } from 'react'
import { API_BASE_URL } from './api'
import type { MarkSettings, PublicEvent } from './types'
import { formatPhone } from '../shared/phone'

/*
 * The studio's mark on photos guests download and share, in the style the studio chose:
 *  - CORNER: the logo and/or a line of text (name, @instagram, phone) in white with a soft shadow, bottom-left,
 *    centre or right. No box.
 *  - STRIP: a warm off-white band added BELOW the photo, so the image is never covered.
 * The server draws the same mark on its plain download link (backend Watermark.java, constants of the same names);
 * keep the numbers in step with it. S = the photo's shorter side, W = its width.
 */
// ---- CORNER ----
/** Distance from the photo's edges, share of S. */
export const MARGIN = 0.03
/** Height of the mark (the logo at the studio's 100 % size), share of S: SMALL / MEDIUM. */
export const HEIGHT_SMALL = 0.042
export const HEIGHT_MEDIUM = 0.056
/** The mark never covers more than this share of W; wider marks are scaled down. */
export const MAX_WIDTH = 0.6
export const OPACITY = 0.92
/** Text size as a share of the mark's height; the gap between logo and text likewise. */
export const TEXT_SIZE = 0.75
export const LOGO_GAP = 0.4
/** A logo without transparency gets corners this round, share of its height. */
export const LOGO_ROUNDING = 0.12
/** Soft shadow behind white text and logo silhouettes: blur = this share of the text size. */
export const SHADOW_BLUR = 0.25
export const SHADOW_COLOR = 'rgba(0,0,0,.45)'
/** A corner logo is this many times the mark's base height, so a logo's small lines stay readable next to the text. */
export const CORNER_LOGO = 1.5
/** A logo is "transparent" when at least this share of its pixels is mostly see-through. */
export const TRANSPARENT_SHARE = 0.005
// ---- STRIP ----
/** The band under the photo: max(STRIP_MIN, STRIP_HEIGHT x W) px, with a 1 px line on top. */
export const STRIP_MIN = 40
export const STRIP_HEIGHT = 0.06
export const STRIP_COLOR = '#FAF8F5'
export const STRIP_LINE = '#E8E2D8'
export const STRIP_TEXT_COLOR = '#2A2520'
/** Logo height and text size as shares of the band; the gap between them; side padding as a share of W. */
export const STRIP_LOGO = 0.6
export const STRIP_TEXT = 0.36
export const STRIP_GAP = 0.25
export const STRIP_PADDING = 0.03

export const SEPARATOR = '  ·  '
const FONT_FAMILY = '"Inter Variable", system-ui, sans-serif'
const JPEG_QUALITY = 0.92

/** What a studio gets until it chooses: its logo and Instagram, small, bottom-right. */
export const DEFAULT_MARK: MarkSettings = { style: 'CORNER', position: 'RIGHT', size: 'SMALL', logo: true, name: false, instagram: true, phone: false }

/** Turns a downloaded photo into the one a guest saves or shares. */
export type Watermarker = (photo: Blob) => Promise<Blob>

/** A logo ready to draw: as it is, plus a white silhouette when it is a cut-out (transparent PNG). */
export interface LogoArt {
  width: number
  height: number
  image: CanvasImageSource
  white?: CanvasImageSource
}

export interface Mark {
  logo: LogoArt | null
  name: string
  instagram?: string | null
  /** Saved like +919876543210; drawn as +91 98765 43210. */
  phone?: string | null
  /** The studio's logo size setting, 50–200 %. */
  logoScale: number
  settings?: MarkSettings
}

const clamp = (n: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, n))
const font = (px: number) => `600 ${px}px ${FONT_FAMILY}`

/**
 * What to draw: the logo when ticked and uploaded, then the name, "@instagram" and phone when ticked and set.
 * With nothing to show, the studio name: a mark is never empty.
 */
export function resolveContent(mark: Mark): { logo: LogoArt | null; texts: string[] } {
  const s = mark.settings ?? DEFAULT_MARK
  const logo = s.logo ? mark.logo : null
  const texts: string[] = []
  if (s.name && mark.name) texts.push(mark.name)
  if (s.instagram && mark.instagram) texts.push(`@${mark.instagram.replace(/^@/, '')}`)
  if (s.phone && mark.phone) texts.push(formatPhone(mark.phone))
  if (!logo && texts.length === 0) texts.push(mark.name)
  return { logo, texts }
}

/** Height of the band a STRIP adds below a photo {@code width} px wide. */
export const bandHeight = (width: number) => Math.max(STRIP_MIN, Math.round(width * STRIP_HEIGHT))

/** The size of the finished image: the photo's, taller by the band for STRIP. */
export function markedSize(width: number, height: number, settings: MarkSettings | undefined): { width: number; height: number } {
  return { width, height: settings?.style === 'STRIP' ? height + bandHeight(width) : height }
}

function startX(position: MarkSettings['position'], width: number, edge: number, total: number) {
  if (position === 'LEFT') return edge
  if (position === 'CENTER') return (width - total) / 2
  return width - edge - total
}

/**
 * Draws the mark for a {@code width} x {@code height} photo already on the canvas. For STRIP the canvas must be
 * {@link markedSize} tall: the band is drawn below the photo.
 */
export function drawMark(ctx: CanvasRenderingContext2D, width: number, height: number, mark: Mark) {
  const settings = mark.settings ?? DEFAULT_MARK
  const { logo, texts } = resolveContent(mark)
  const text = texts.join(SEPARATOR)
  ctx.save()
  try {
    if (settings.style === 'STRIP') drawStrip(ctx, width, height, logo, text, settings.position)
    else drawCorner(ctx, width, height, logo, text, settings, clamp(mark.logoScale || 100, 50, 200) / 100)
  } finally {
    ctx.restore()
  }
}

function drawCorner(ctx: CanvasRenderingContext2D, width: number, height: number, logo: LogoArt | null, text: string, s: MarkSettings, scale: number) {
  const shortSide = Math.min(width, height)
  const margin = Math.max(6, Math.round(shortSide * MARGIN))
  const base = shortSide * (s.size === 'MEDIUM' ? HEIGHT_MEDIUM : HEIGHT_SMALL)
  let fontPx = Math.max(6, base * TEXT_SIZE)
  let logoH = logo ? base * CORNER_LOGO * scale : 0
  let logoW = logo ? (logoH * logo.width) / logo.height : 0
  let gap = logo && text ? base * LOGO_GAP : 0
  const measure = () => {
    if (!text) return 0
    ctx.font = font(fontPx)
    return ctx.measureText(text).width
  }
  let total = logoW + gap + measure()
  const fit = Math.min(1, (width * MAX_WIDTH) / total)
  if (fit < 1) {
    logoH *= fit
    logoW *= fit
    gap *= fit
    fontPx *= fit
    total = logoW + gap + measure()
  }
  const lineH = Math.max(logoH, fontPx)
  const x = startX(s.position, width, margin, total)
  const cy = height - margin - lineH / 2

  ctx.globalAlpha = OPACITY
  ctx.shadowColor = SHADOW_COLOR
  ctx.shadowBlur = fontPx * SHADOW_BLUR
  if (logo) {
    const ly = cy - logoH / 2
    if (logo.white) {
      ctx.drawImage(logo.white, x, ly, logoW, logoH)
    } else {
      ctx.shadowColor = 'transparent'
      ctx.beginPath()
      if (ctx.roundRect) ctx.roundRect(x, ly, logoW, logoH, logoH * LOGO_ROUNDING)
      else ctx.rect(x, ly, logoW, logoH)
      ctx.clip()
      ctx.drawImage(logo.image, x, ly, logoW, logoH)
      ctx.shadowColor = SHADOW_COLOR
    }
  }
  if (text) {
    ctx.font = font(fontPx)
    ctx.fillStyle = '#fff'
    ctx.textBaseline = 'middle'
    ctx.fillText(text, x + logoW + gap, cy)
  }
}

function drawStrip(ctx: CanvasRenderingContext2D, width: number, height: number, logo: LogoArt | null, text: string, position: MarkSettings['position']) {
  const band = bandHeight(width)
  ctx.fillStyle = STRIP_COLOR
  ctx.fillRect(0, height, width, band)
  ctx.fillStyle = STRIP_LINE
  ctx.fillRect(0, height, width, 1)

  const pad = width * STRIP_PADDING
  let logoH = logo ? band * STRIP_LOGO : 0
  let logoW = logo ? (logoH * logo.width) / logo.height : 0
  let gap = logo && text ? band * STRIP_GAP : 0
  let fontPx = band * STRIP_TEXT
  const measure = () => {
    if (!text) return 0
    ctx.font = font(fontPx)
    return ctx.measureText(text).width
  }
  let total = logoW + gap + measure()
  const fit = Math.min(1, (width - 2 * pad) / total)
  if (fit < 1) {
    logoH *= fit
    logoW *= fit
    gap *= fit
    fontPx *= fit
    total = logoW + gap + measure()
  }
  const x = startX(position, width, pad, total)
  const cy = height + band / 2
  if (logo) ctx.drawImage(logo.image, x, cy - logoH / 2, logoW, logoH)
  if (text) {
    ctx.font = font(fontPx)
    ctx.fillStyle = STRIP_TEXT_COLOR
    ctx.textBaseline = 'middle'
    ctx.fillText(text, x + logoW + gap, cy)
  }
}

async function decode(blob: Blob): Promise<ImageBitmap> {
  return createImageBitmap(blob)
}

/** True when the logo has real see-through areas (a PNG cut-out), not just soft edges or none at all. */
function hasTransparency(img: CanvasImageSource & { width: number; height: number }): boolean {
  const scale = Math.min(1, 200 / Math.max(img.width, img.height))
  const w = Math.max(1, Math.round(img.width * scale))
  const h = Math.max(1, Math.round(img.height * scale))
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  const ctx = canvas.getContext('2d', { willReadFrequently: true })
  if (!ctx) return false
  ctx.drawImage(img, 0, 0, w, h)
  const data = ctx.getImageData(0, 0, w, h).data
  let clear = 0
  for (let i = 3; i < data.length; i += 4) if (data[i] < 128) clear++
  return clear >= w * h * TRANSPARENT_SHARE
}

/** The logo ready to draw; a transparent one also gets a white silhouette (dark logos stay visible on any photo). */
export function prepareLogo(img: CanvasImageSource & { width: number; height: number }): LogoArt {
  const art: LogoArt = { width: img.width, height: img.height, image: img }
  try {
    if (hasTransparency(img)) {
      const canvas = document.createElement('canvas')
      canvas.width = img.width
      canvas.height = img.height
      const ctx = canvas.getContext('2d')
      if (ctx) {
        ctx.drawImage(img, 0, 0)
        ctx.globalCompositeOperation = 'source-in'
        ctx.fillStyle = '#fff'
        ctx.fillRect(0, 0, canvas.width, canvas.height)
        art.white = canvas
      }
    }
  } catch {
    // Unreadable pixels: draw the logo as it is.
  }
  return art
}

/** The photo with the mark, as a JPEG (taller than the photo for STRIP). */
export async function markPhoto(photo: Blob, mark: Mark): Promise<Blob> {
  const img = await decode(photo)
  const canvas = document.createElement('canvas')
  try {
    const size = markedSize(img.width, img.height, mark.settings)
    canvas.width = size.width
    canvas.height = size.height
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('No canvas')
    ctx.drawImage(img, 0, 0)
    drawMark(ctx, img.width, img.height, mark)
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not make the photo'))), 'image/jpeg', JPEG_QUALITY))
  } finally {
    img.close?.()
    canvas.width = 0
    canvas.height = 0
  }
}

/** The owner's logo through the API (a canvas can only read images served with CORS); null when it has none. */
async function loadLogo(slug: string): Promise<LogoArt | null> {
  try {
    const res = await fetch(`${API_BASE_URL}/api/public/events/${encodeURIComponent(slug)}/logo`, { mode: 'cors', credentials: 'omit' })
    if (!res.ok) return null
    return prepareLogo(await decode(await res.blob()))
  } catch {
    return null
  }
}

/**
 * The watermarker for an event's guest gallery, or undefined when its watermark is off (photos stay as they are).
 * The logo is fetched once, on the first photo that needs it. The text details come from the event's brand.
 */
export function useWatermarker(event: Pick<PublicEvent, 'slug' | 'watermark' | 'brand' | 'name'>): Watermarker | undefined {
  const { slug, watermark, brand } = event
  const name = brand?.name ?? 'McreatiK Studios'
  const logoUrl = brand?.logoUrl ?? null
  const logoScale = brand?.logoScale ?? 100
  const instagram = brand?.instagram ?? null
  const phone = brand?.phone ?? null
  const settingsKey = JSON.stringify(brand?.mark ?? DEFAULT_MARK)
  return useMemo(() => {
    if (!watermark) return undefined
    const settings = JSON.parse(settingsKey) as MarkSettings
    let logo: Promise<LogoArt | null> | null = null
    return async (photo: Blob) => {
      logo ??= logoUrl ? loadLogo(slug) : Promise.resolve(null)
      return markPhoto(photo, { logo: await logo, name, instagram, phone, logoScale, settings })
    }
    // logoUrl changes when the studio uploads a new logo: fetch that one.
  }, [watermark, slug, name, logoUrl, logoScale, instagram, phone, settingsKey])
}
