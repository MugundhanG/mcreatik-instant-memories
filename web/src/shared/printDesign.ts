import { jpegStripEncoder } from './jpegEncoder'
import { formatPhone } from './phone'

/**
 * Print files for the reusable studio board (18 × 24 in) and the per-event cards (8 × 12 in).
 *
 * Each design is one SVG drawn in print pixels (300 per inch), with a 0.125 in bleed on every side: the background
 * runs past the cut line so a slightly off cut never leaves a white edge, and all text and ornaments stay inside a
 * safe zone (0.5 in on the board, 0.4 in on the cards). The same SVG is the on-screen preview and, drawn onto a
 * canvas, the downloaded JPEG.
 */

export type BoardTheme = 'cream' | 'dark' | 'white'

export const BOARD_THEMES: BoardTheme[] = ['cream', 'dark', 'white']

/** Print pixels per inch. */
export const DPI = 300
const I = DPI
export const BLEED = 0.125 * I

/** Trim size in inches and the file size in pixels (trim plus bleed on both sides). */
export const BOARD = { widthIn: 18, heightIn: 24, width: 18 * I + 2 * BLEED, height: 24 * I + 2 * BLEED, safeIn: 0.5 }
export const CARD = { widthIn: 8, heightIn: 12, width: 8 * I + 2 * BLEED, height: 12 * I + 2 * BLEED, safeIn: 0.4 }

const GOLD = '#B07A2A'
const GOLD_LIGHT = '#D9A95C'
const GOLD_DEEP = '#7A5318'
const INK = '#1A1714'
const LIVE_RED = '#D23B3B'

interface Theme {
  label: string
  /** Board background (an SVG paint: a colour or url(#id) of a gradient in `defs`). */
  boardBg: string
  cardBg: string
  defs: string
  text: string
  muted: string
  accent: string
  frame: string
  slot: string
  cardName: string
  cardLine: string
  /** Swatch colour for the theme picker. */
  swatch: string
}

export const THEMES: Record<BoardTheme, Theme> = {
  cream: {
    label: 'Cream',
    boardBg: 'url(#board-bg)',
    cardBg: 'url(#card-bg)',
    defs: `<radialGradient id="board-bg" cx="50%" cy="30%" r="80%"><stop offset="0" stop-color="#FFFAF0"/><stop offset="0.6" stop-color="#FBF6EC"/><stop offset="1" stop-color="#F3E8D3"/></radialGradient>
<linearGradient id="card-bg" x1="0.2" y1="0" x2="0.8" y2="1"><stop offset="0" stop-color="#FFFDF8"/><stop offset="1" stop-color="#F8EFDF"/></linearGradient>`,
    text: INK,
    muted: '#6E675F',
    accent: GOLD_DEEP,
    frame: GOLD_LIGHT,
    slot: '#FFFFFF',
    cardName: GOLD,
    cardLine: GOLD_LIGHT,
    swatch: '#F8EFDF',
  },
  dark: {
    label: 'Dark',
    boardBg: 'url(#board-bg)',
    cardBg: 'url(#card-bg)',
    defs: `<radialGradient id="board-bg" cx="50%" cy="30%" r="75%"><stop offset="0" stop-color="#2B2521"/><stop offset="0.65" stop-color="#1A1714"/><stop offset="1" stop-color="#1A1714"/></radialGradient>
<linearGradient id="card-bg" x1="0.2" y1="0" x2="0.8" y2="1"><stop offset="0" stop-color="#25201C"/><stop offset="1" stop-color="#1A1714"/></linearGradient>`,
    text: '#FFFFFF',
    muted: '#A8A29A',
    accent: GOLD_LIGHT,
    frame: GOLD,
    slot: '#221E1A',
    cardName: GOLD_LIGHT,
    cardLine: GOLD,
    swatch: '#1A1714',
  },
  white: {
    label: 'White',
    boardBg: '#FFFFFF',
    cardBg: '#FFFFFF',
    defs: '',
    text: INK,
    muted: '#7A746D',
    accent: GOLD_DEEP,
    frame: '#E3D3B5',
    slot: '#FAF8F4',
    cardName: GOLD,
    cardLine: '#E3D3B5',
    swatch: '#FFFFFF',
  },
}

export function isBoardTheme(value: unknown): value is BoardTheme {
  return value === 'cream' || value === 'dark' || value === 'white'
}

/** Width of a line of text in pixels; `font` is a CSS font shorthand. */
export type Measure = (text: string, font: string) => number

let measureCtx: CanvasRenderingContext2D | null | undefined
/** Measures with the page's own fonts (the same files the SVG embeds); falls back to an estimate. */
export const measureText: Measure = (text, font) => {
  if (measureCtx === undefined) {
    try {
      measureCtx = document.createElement('canvas').getContext('2d')
    } catch {
      measureCtx = null
    }
  }
  if (measureCtx) {
    measureCtx.font = font
    const w = measureCtx.measureText(text).width
    if (w > 0) return w
  }
  const size = Number(/(\d+(?:\.\d+)?)px/.exec(font)?.[1] ?? 16)
  return text.length * size * 0.52
}

const SERIF = "'Cormorant Garamond', Georgia, serif"
const SANS = "'Inter Variable', Inter, Arial, sans-serif"

function esc(text: string) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

/** Largest size up to `base` at which the text (with its letter spacing) fits `maxWidth`. */
function fit(measure: Measure, text: string, weight: number, family: string, base: number, maxWidth: number, spacingEm = 0) {
  const w = measure(text, `${weight} ${base}px ${family}`) + spacingEm * base * text.length
  return w <= maxWidth ? base : Math.floor(base * (maxWidth / w))
}

/** The couple's names on one line, or two (split at "&") when one line would get too small. */
function nameLines(measure: Measure, names: string, base: number, maxWidth: number): { lines: string[]; size: number } {
  const one = fit(measure, names, 600, SERIF, base, maxWidth)
  if (one >= base * 0.72) return { lines: [names], size: one }
  const amp = names.indexOf(' & ')
  const words = names.split(' ')
  const lines = amp > 0
    ? [names.slice(0, amp), names.slice(amp + 1)]
    : [words.slice(0, Math.ceil(words.length / 2)).join(' '), words.slice(Math.ceil(words.length / 2)).join(' ')]
  const size = Math.min(...lines.map((l) => fit(measure, l, 600, SERIF, base, maxWidth)))
  return { lines, size }
}

/** The fonts as @font-face rules with data: URLs, so the SVG renders the same as an image and on a canvas. */
export interface EmbeddedFonts {
  css: string
}

export const NO_FONTS: EmbeddedFonts = { css: '' }

function svgOpen(width: number, height: number, theme: Theme, fonts: EmbeddedFonts) {
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
<defs><style>${fonts.css}</style>${theme.defs}</defs>`
}

/** Places a QR code SVG (from `qrSvg`) at x, y with the given size. */
function placeQr(qr: string, x: number, y: number, size: number) {
  return qr.replace(/<\?xml[^>]*>/, '').replace('<svg ', `<svg x="${x}" y="${y}" width="${size}" height="${size}" `)
}

export interface CardContent {
  names: string
  /** "WEDDING · 3 NOVEMBER 2026", or just the date. */
  detail: string
  /** QR code SVG markup (see `qrSvg`). */
  qr: string
}

/** The card's artwork at trim size (0 … 8 in × 0 … 12 in), without bleed. */
function cardArtwork(theme: Theme, card: CardContent, table: boolean, measure: Measure) {
  const W = 8 * I
  const H = 12 * I
  const inset = 0.45 * I
  const name = nameLines(measure, card.names, 1.0 * I, W - 2 * inset - 0.5 * I)
  const lineH = name.size * 1.08
  const detailSize = fit(measure, card.detail, 500, SANS, 0.24 * I, W - 2 * inset - 0.4 * I, 0.25)
  const qrBox = 5.0 * I
  const scanSize = 0.6 * I
  const total = name.lines.length * lineH + 0.24 * I + detailSize + 0.62 * I + qrBox + (table ? 0.5 * I + scanSize : 0)
  let y = (H - total) / 2
  const parts: string[] = []
  parts.push(`<rect x="${inset}" y="${inset}" width="${W - 2 * inset}" height="${H - 2 * inset}" fill="none" stroke="${theme.cardLine}" stroke-width="${0.03 * I}"/>`)
  for (const line of name.lines) {
    y += lineH
    parts.push(`<text x="${W / 2}" y="${y - lineH * 0.2}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${name.size}" fill="${theme.cardName}">${esc(line)}</text>`)
  }
  y += 0.24 * I + detailSize
  const ls = detailSize * 0.25
  parts.push(`<text x="${W / 2 + ls / 2}" y="${y}" text-anchor="middle" font-family="${SANS}" font-weight="500" font-size="${detailSize}" letter-spacing="${ls}" fill="${theme.text}">${esc(card.detail)}</text>`)
  y += 0.62 * I
  const qx = (W - qrBox) / 2
  parts.push(`<rect x="${qx}" y="${y}" width="${qrBox}" height="${qrBox}" rx="${0.36 * I}" fill="#FFFFFF" stroke="${GOLD}" stroke-width="${0.06 * I}"/>`)
  const pad = 0.24 * I
  parts.push(placeQr(card.qr, qx + pad, y + pad, qrBox - 2 * pad))
  y += qrBox
  if (table) {
    y += 0.5 * I + scanSize * 0.8
    const size = fit(measure, 'Scan to see the photos live', 600, SERIF, scanSize, W - 2 * inset - 0.4 * I)
    parts.push(`<text x="${W / 2}" y="${y}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${size}" fill="${theme.text}">Scan to see the photos live</text>`)
  }
  return `<rect x="0" y="0" width="${W}" height="${H}" fill="${theme.cardBg}"/>${parts.join('\n')}`
}

/** An event card (`table: false`, for the board) or table card (`table: true`, stands alone): 8 × 12 in plus bleed. */
export function cardSvg(themeName: BoardTheme, card: CardContent, opts: { table: boolean; fonts?: EmbeddedFonts; measure?: Measure }) {
  const theme = THEMES[themeName]
  return `${svgOpen(CARD.width, CARD.height, theme, opts.fonts ?? NO_FONTS)}
<rect x="0" y="0" width="${CARD.width}" height="${CARD.height}" fill="${theme.cardBg}"/>
<g transform="translate(${BLEED} ${BLEED})">${cardArtwork(theme, card, opts.table, opts.measure ?? measureText)}</g>
</svg>`
}

export interface BoardBrand {
  name: string
  /** Logo as a data: URL (so the SVG has no outside references), or null for the name alone. */
  logo: string | null
  /** Percent of the standard logo size (the studio's poster logo size, 50–200). */
  logoScale: number
  instagram: string | null
  /** Contact number, printed above the Instagram handle. */
  phone?: string | null
}

const PHONE_ICON = (x: number, y: number, s: number, color: string) =>
  `<g transform="translate(${x} ${y}) scale(${s / 24})" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round"><path d="M5 3.5h3.2l1.6 4.2-2.2 1.5a11.5 11.5 0 0 0 7.2 7.2l1.5-2.2 4.2 1.6V19a2 2 0 0 1-2.2 2A17 17 0 0 1 3 5.7 2 2 0 0 1 5 3.5Z"/></g>`

const INSTAGRAM_ICON = (x: number, y: number, s: number, color: string) =>
  `<g transform="translate(${x} ${y}) scale(${s / 24})" fill="none" stroke="${color}" stroke-width="2"><rect x="2" y="2" width="20" height="20" rx="5"/><circle cx="12" cy="12" r="4.5"/><circle cx="17.6" cy="6.4" r="0.9" fill="${color}" stroke="none"/></g>`

/**
 * The studio board: 18 × 24 in plus bleed, printed once and reused. Its frame holds an 8 × 12 in event card;
 * pass `card` to preview the board with one in place (the downloaded board has an empty frame).
 */
export function boardSvg(themeName: BoardTheme, brand: BoardBrand, opts: { card?: CardContent; fonts?: EmbeddedFonts; measure?: Measure } = {}) {
  const theme = THEMES[themeName]
  const measure = opts.measure ?? measureText
  const W = 18 * I
  const parts: string[] = []

  // Frame and gold corners (all inside the 0.5 in safe zone).
  const inset = 0.8 * I
  parts.push(`<rect x="${inset}" y="${inset}" width="${W - 2 * inset}" height="${24 * I - 2 * inset}" fill="none" stroke="${theme.frame}" stroke-width="${0.03 * I}"/>`)
  const c = 0.6 * I
  const len = 1.6 * I
  const R = W - c
  const B = 24 * I - c
  for (const d of [`M${c} ${c + len}V${c}H${c + len}`, `M${R - len} ${c}H${R}V${c + len}`, `M${c} ${B - len}V${B}H${c + len}`, `M${R - len} ${B}H${R}V${B - len}`]) {
    parts.push(`<path d="${d}" fill="none" stroke="${GOLD}" stroke-width="${0.1 * I}"/>`)
  }

  // Logo, studio name, "Live photo gallery" pill and headline, centred above the card frame.
  const logoH = brand.logo ? Math.min(2.0 * I, Math.max(0.9 * I, 1.5 * I * (brand.logoScale / 100))) : 0
  const stack = (brand.logo ? logoH + 0.8 * I : 0.6 * I) + 0.75 * I + 1.25 * I
  let y = 1.25 * I + (5.1 * I - stack) / 2
  if (brand.logo) {
    const logoW = 7 * I
    parts.push(`<image x="${(W - logoW) / 2}" y="${y}" width="${logoW}" height="${logoH}" preserveAspectRatio="xMidYMid meet" href="${brand.logo}" xlink:href="${brand.logo}"/>`)
    y += logoH + 0.8 * I
  } else {
    y += 0.6 * I
  }
  const studio = brand.name.toUpperCase()
  const studioSize = fit(measure, studio, 600, SERIF, 0.6 * I, 13 * I, 0.27)
  const studioLs = studioSize * 0.27
  parts.push(`<text x="${W / 2 + studioLs / 2}" y="${y}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${studioSize}" letter-spacing="${studioLs}" fill="${theme.accent}">${esc(studio)}</text>`)

  y += 0.75 * I
  const pillText = 'LIVE PHOTO GALLERY'
  const pillSize = 0.26 * I
  const pillLs = pillSize * 0.25
  const pillTextW = measure(pillText, `600 ${pillSize}px ${SANS}`) + pillLs * pillText.length
  const pillW = pillTextW + 0.95 * I
  const pillH = 0.56 * I
  const px = (W - pillW) / 2
  parts.push(`<rect x="${px}" y="${y - pillH / 2}" width="${pillW}" height="${pillH}" rx="${pillH / 2}" fill="none" stroke="${theme.frame}" stroke-width="${0.03 * I}"/>`)
  parts.push(`<circle cx="${px + 0.38 * I}" cy="${y}" r="${0.09 * I}" fill="${LIVE_RED}"/>`)
  parts.push(`<text x="${px + 0.62 * I}" y="${y + pillSize * 0.36}" font-family="${SANS}" font-weight="600" font-size="${pillSize}" letter-spacing="${pillLs}" fill="${theme.accent}">${pillText}</text>`)

  y += 1.25 * I
  parts.push(`<text x="${W / 2}" y="${y}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${1.0 * I}" fill="${theme.text}">Every moment, <tspan font-style="italic" font-weight="500" fill="${GOLD}">live.</tspan></text>`)

  // The frame the event card sits in: 8 × 12 in plus a little room.
  const slotX = 4.8 * I
  const slotY = 6.45 * I
  parts.push(`<rect x="${slotX}" y="${slotY}" width="${8.4 * I}" height="${12.4 * I}" rx="${0.28 * I}" fill="${theme.slot}" stroke="${theme.frame}" stroke-width="${0.04 * I}"/>`)
  if (opts.card) {
    parts.push(`<g transform="translate(${slotX + 0.2 * I} ${slotY + 0.2 * I})">${cardArtwork(theme, opts.card, false, measure)}</g>`)
  } else {
    parts.push(`<text x="${W / 2}" y="${slotY + 6.2 * I}" text-anchor="middle" font-family="${SANS}" font-weight="600" font-size="${0.22 * I}" letter-spacing="${0.05 * I}" fill="${theme.frame}">EVENT CARD · 8 × 12 IN</text>`)
  }

  // "Scan to see the photos live" and the three steps.
  parts.push(`<text x="${W / 2}" y="${19.85 * I}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${0.64 * I}" fill="${theme.text}">Scan to see the photos live</text>`)
  const stepSize = 0.27 * I
  const n = (d: string) => `<tspan fill="${GOLD}" font-weight="700">${d}</tspan>`
  const dash = `<tspan fill="${theme.frame}">  —  </tspan>`
  parts.push(`<text x="${W / 2 + stepSize * 0.1}" y="${20.55 * I}" text-anchor="middle" font-family="${SANS}" font-weight="500" font-size="${stepSize}" letter-spacing="${stepSize * 0.2}" fill="${theme.text}">${n('1')}  SCAN${dash}${n('2')}  WATCH LIVE${dash}${n('3')}  SAVE &amp; SHARE</text>`)

  // Footer.
  const left = 1.6 * I
  const footSize = 0.22 * I
  const footLs = footSize * 0.18
  parts.push(`<text x="${left}" y="${22.5 * I}" font-family="${SANS}" font-weight="500" font-size="${footSize}" letter-spacing="${footLs}" fill="${theme.muted}">NO APP · NO SIGN-UP NEEDED</text>`)
  parts.push(`<text x="${left}" y="${22.92 * I}" font-family="${SANS}" font-weight="500" font-size="${footSize}" letter-spacing="${footLs}" fill="${theme.muted}">POWERED BY MCREATIK INSTANT MEMORIES</text>`)
  // Bottom right: the contact number above the Instagram handle (each optional; the lower slot is used first).
  const contact: { text: string; icon: typeof INSTAGRAM_ICON }[] = []
  if (brand.phone) contact.push({ text: formatPhone(brand.phone), icon: PHONE_ICON })
  if (brand.instagram) contact.push({ text: `@${brand.instagram.replace(/^@/, '')}`, icon: INSTAGRAM_ICON })
  contact.forEach(({ text, icon }, i) => {
    const size = 0.3 * I
    const right = W - 1.6 * I
    const y = (contact.length === 2 && i === 0 ? 22.42 : 22.92) * I
    const w = measure(text, `500 ${size}px ${SANS}`)
    parts.push(`<text x="${right}" y="${y}" text-anchor="end" font-family="${SANS}" font-weight="500" font-size="${size}" fill="${theme.accent}">${esc(text)}</text>`)
    parts.push(icon(right - w - 0.45 * I, y - size * 0.88, size * 1.05, theme.accent))
  })

  return `${svgOpen(BOARD.width, BOARD.height, theme, opts.fonts ?? NO_FONTS)}
<rect x="0" y="0" width="${BOARD.width}" height="${BOARD.height}" fill="${theme.boardBg}"/>
<g transform="translate(${BLEED} ${BLEED})">${parts.join('\n')}</g>
</svg>`
}

/** The classic A4 poster: 210 × 297 mm at 300 dpi, edge to edge (no bleed). */
export const POSTER = { width: 2480, height: 3508 }

/** The A4 stage / table poster (ivory paper, gold frame) as one SVG, for the preview and the 300 dpi JPEG. */
export function posterSvg(content: CardContent, brand: BoardBrand, opts: { fonts?: EmbeddedFonts; measure?: Measure } = {}) {
  const measure = opts.measure ?? measureText
  const W = POSTER.width
  const H = POSTER.height
  const parts: string[] = []
  parts.push(`<rect x="0" y="0" width="${W}" height="${H}" fill="#FBF6EA"/>`)
  parts.push(`<rect x="${0.45 * I}" y="${0.45 * I}" width="${W - 0.9 * I}" height="${H - 0.9 * I}" fill="none" stroke="#C9A35A" stroke-width="${0.03 * I}"/>`)
  parts.push(`<rect x="${0.6 * I}" y="${0.6 * I}" width="${W - 1.2 * I}" height="${H - 1.2 * I}" fill="none" stroke="#E3CC9A" stroke-width="${0.012 * I}"/>`)
  let y = 1.0 * I
  if (brand.logo) {
    const logoH = Math.min(1.3 * I, Math.max(0.6 * I, 0.9 * I * (brand.logoScale / 100)))
    const logoW = 4.5 * I
    parts.push(`<image x="${(W - logoW) / 2}" y="${y}" width="${logoW}" height="${logoH}" preserveAspectRatio="xMidYMid meet" href="${brand.logo}" xlink:href="${brand.logo}"/>`)
    y += logoH + 0.2 * I
  }
  const studio = brand.name.toUpperCase()
  const studioSize = fit(measure, studio, 600, SERIF, 0.5 * I, W - 2.4 * I, 0.22)
  const studioLs = studioSize * 0.22
  y += studioSize
  parts.push(`<text x="${W / 2 + studioLs / 2}" y="${y}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${studioSize}" letter-spacing="${studioLs}" fill="${GOLD_DEEP}">${esc(studio)}</text>`)
  y += 0.55 * I
  const pill = 'LIVE PHOTO GALLERY'
  const pillSize = 0.2 * I
  const pillLs = pillSize * 0.3
  const pillW = measure(pill, `600 ${pillSize}px ${SANS}`) + pillLs * pill.length + 1.0 * I
  const pillH = 0.5 * I
  const px = (W - pillW) / 2
  parts.push(`<rect x="${px}" y="${y}" width="${pillW}" height="${pillH}" rx="${pillH / 2}" fill="none" stroke="${GOLD_LIGHT}" stroke-width="${0.015 * I}"/>`)
  parts.push(`<circle cx="${px + 0.35 * I}" cy="${y + pillH / 2}" r="${0.07 * I}" fill="${LIVE_RED}"/>`)
  parts.push(`<text x="${px + 0.6 * I}" y="${y + pillH / 2 + pillSize * 0.36}" font-family="${SANS}" font-weight="600" font-size="${pillSize}" letter-spacing="${pillLs}" fill="${GOLD_DEEP}">${pill}</text>`)
  y += pillH + 0.3 * I
  const name = nameLines(measure, content.names, 1.1 * I, W - 2.4 * I)
  const lineH = name.size * 1.1
  for (const line of name.lines) {
    y += lineH
    parts.push(`<text x="${W / 2}" y="${y - lineH * 0.2}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${name.size}" fill="${GOLD}">${esc(line)}</text>`)
  }
  const detailSize = fit(measure, content.detail, 500, SANS, 0.19 * I, W - 2.4 * I, 0.3)
  y += 0.15 * I + detailSize
  const dls = detailSize * 0.3
  parts.push(`<text x="${W / 2 + dls / 2}" y="${y}" text-anchor="middle" font-family="${SANS}" font-weight="500" font-size="${detailSize}" letter-spacing="${dls}" fill="#5B4A33">${esc(content.detail)}</text>`)
  y += 0.45 * I
  const qrBox = 4.6 * I
  const qx = (W - qrBox) / 2
  parts.push(`<rect x="${qx}" y="${y}" width="${qrBox}" height="${qrBox}" rx="${0.3 * I}" fill="#FFFFFF" stroke="${GOLD}" stroke-width="${0.05 * I}"/>`)
  parts.push(placeQr(content.qr, qx + 0.25 * I, y + 0.25 * I, qrBox - 0.5 * I))
  y += qrBox + 0.35 * I + 0.62 * I * 0.8
  const scan = fit(measure, 'Scan to see the photos live', 600, SERIF, 0.62 * I, W - 2.4 * I)
  parts.push(`<text x="${W / 2}" y="${y}" text-anchor="middle" font-family="${SERIF}" font-weight="600" font-size="${scan}" fill="#2B2118">Scan to see the photos live</text>`)
  const step = 0.2 * I
  const n = (d: string) => `<tspan fill="${GOLD}" font-weight="700">${d}</tspan>`
  y += 0.5 * I
  parts.push(`<text x="${W / 2}" y="${y}" text-anchor="middle" font-family="${SANS}" font-weight="500" font-size="${step}" letter-spacing="${step * 0.2}" fill="#5B4A33">${n('1')} SCAN     ${n('2')} WATCH LIVE     ${n('3')} SAVE</text>`)
  const left = 1.0 * I
  const foot = 0.18 * I
  parts.push(`<text x="${left}" y="${H - 1.0 * I}" font-family="${SANS}" font-weight="500" font-size="${foot}" letter-spacing="${foot * 0.2}" fill="#8A7A66">NO APP · NO SIGN-UP NEEDED</text>`)
  const contact: { text: string; icon: typeof INSTAGRAM_ICON }[] = []
  if (brand.phone) contact.push({ text: formatPhone(brand.phone), icon: PHONE_ICON })
  if (brand.instagram) contact.push({ text: `@${brand.instagram.replace(/^@/, '')}`, icon: INSTAGRAM_ICON })
  contact.forEach(({ text, icon }, i) => {
    const size = 0.26 * I
    const right = W - 1.0 * I
    const cy = H - 1.0 * I - (contact.length - 1 - i) * 0.45 * I
    const w = measure(text, `500 ${size}px ${SANS}`)
    parts.push(`<text x="${right}" y="${cy}" text-anchor="end" font-family="${SANS}" font-weight="500" font-size="${size}" fill="${GOLD_DEEP}">${esc(text)}</text>`)
    parts.push(icon(right - w - 0.4 * I, cy - size * 0.88, size * 1.05, GOLD_DEEP))
  })
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">
<defs><style>${(opts.fonts ?? NO_FONTS).css}</style></defs>
${parts.join('\n')}
</svg>`
}

// ---- Fonts, images and export (browser only) ----

export async function dataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error ?? new Error('Could not read file'))
    reader.readAsDataURL(blob)
  })
}

/** Fetches an image (a studio logo) as a data: URL; null when it can't be loaded. */
export async function imageDataUrl(url: string | null): Promise<string | null> {
  if (!url) return null
  try {
    const res = await fetch(url, { mode: 'cors', credentials: 'omit' })
    if (!res.ok) return null
    return await dataUrl(await res.blob())
  } catch {
    return null
  }
}

let fontsPromise: Promise<EmbeddedFonts> | null = null
/** The three font files the designs use, embedded once per page load. */
export function embeddedFonts(): Promise<EmbeddedFonts> {
  fontsPromise ??= (async () => {
    const [{ default: serif600 }, { default: serif500i }, { default: sans }] = await Promise.all([
      import('@fontsource/cormorant-garamond/files/cormorant-garamond-latin-600-normal.woff2?url'),
      import('@fontsource/cormorant-garamond/files/cormorant-garamond-latin-500-italic.woff2?url'),
      import('@fontsource-variable/inter/files/inter-latin-wght-normal.woff2?url'),
    ])
    const load = async (url: string) => dataUrl(await (await fetch(url)).blob())
    const [a, b, c] = await Promise.all([load(serif600), load(serif500i), load(sans)])
    // Measurements use the page's copies of the same fonts.
    await Promise.all([
      document.fonts?.load(`600 40px "Cormorant Garamond"`),
      document.fonts?.load(`italic 500 40px "Cormorant Garamond"`),
      document.fonts?.load(`500 40px "Inter Variable"`),
    ].filter(Boolean)).catch(() => undefined)
    return {
      css: `@font-face{font-family:'Cormorant Garamond';font-style:normal;font-weight:600;src:url(${a}) format('woff2')}
@font-face{font-family:'Cormorant Garamond';font-style:italic;font-weight:500;src:url(${b}) format('woff2')}
@font-face{font-family:'Inter Variable';font-style:normal;font-weight:100 900;src:url(${c}) format('woff2')}`,
    }
  })().catch((e: unknown) => {
    fontsPromise = null
    throw e
  })
  return fontsPromise
}

/** An SVG as a data: URL, for previews (an <img> needs no clean-up for it). */
export function svgDataUrl(svg: string) {
  return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`
}

export function svgUrl(svg: string) {
  return URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }))
}

/** Sets a JPEG's JFIF header to 300 pixels per inch, so print shops read the right size. */
export function withPrintDpi(bytes: Uint8Array, dpi = DPI): Uint8Array {
  const jfif = bytes[2] === 0xff && bytes[3] === 0xe0 && bytes[6] === 0x4a && bytes[7] === 0x46 && bytes[8] === 0x49 && bytes[9] === 0x46 && bytes[10] === 0
  if (!jfif) return bytes
  const out = bytes.slice()
  out[13] = 1 // dots per inch
  out[14] = dpi >> 8
  out[15] = dpi & 0xff
  out[16] = dpi >> 8
  out[17] = dpi & 0xff
  return out
}

/** Rows drawn and encoded at a time: small enough for any phone's canvas, a multiple of 8 for the encoder. */
const STRIP_ROWS = 512

/**
 * Draws the SVG at full print size and returns a very high-quality JPEG (300 ppi, full colour). It is drawn and
 * encoded a strip at a time, so phones (which cannot hold a canvas this large) get the same full-size file as a
 * computer. {@code onProgress} gets 0 to 100.
 */
export async function renderJpeg(svg: string, width: number, height: number, onProgress?: (percent: number) => void): Promise<Blob> {
  const url = svgUrl(svg)
  const canvas = document.createElement('canvas')
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    canvas.width = width
    canvas.height = Math.min(STRIP_ROWS, height)
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    if (!ctx) throw new Error('This browser cannot make the print file. Try Chrome or Edge on a computer.')
    const encoder = jpegStripEncoder(width, height, { quality: 95, dpi: DPI })
    for (let y = 0; y < height; y += STRIP_ROWS) {
      const rows = Math.min(STRIP_ROWS, height - y)
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, width, rows)
      ctx.drawImage(img, 0, -y, width, height)
      encoder.addStrip(ctx.getImageData(0, 0, width, rows).data, rows)
      onProgress?.(Math.round(((y + rows) / height) * 100))
      await new Promise((resolve) => setTimeout(resolve, 0)) // let the page repaint (the percentage) between strips
    }
    return encoder.finish()
  } finally {
    canvas.width = 0
    canvas.height = 0
    URL.revokeObjectURL(url)
  }
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/** "arun-priya" from "Arun & Priya". */
export function fileSlug(text: string) {
  return text.toLowerCase().normalize('NFKD').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 40) || 'event'
}
