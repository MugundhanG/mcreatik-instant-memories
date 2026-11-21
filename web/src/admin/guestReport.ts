import { adminApi } from '../lib/api'
import { formatCount, splitEventName } from '../lib/format'
import { withDownloadParam } from '../lib/photoActions'
import type { GuestAnalytics, TopPhoto } from '../lib/types'
import { jpegPagesToPdf, type PdfPage } from '../shared/pdf'
import { dataUrl, downloadBlob, embeddedFonts, fileSlug, measureText, type EmbeddedFonts, type Measure } from '../shared/printDesign'
import { buckets, type Bucket } from './analyticsBuckets'

/**
 * The "Generate report" files for an event's guest activity: a one-page summary (a JPEG, easy to send on WhatsApp)
 * and a PDF with that summary plus the full numbers. Every page is an SVG drawn at A4 size, embedded fonts and
 * thumbnails included, then painted onto a canvas and saved as JPEG. A PDF page is just that JPEG.
 */

/** A4 portrait at 150 dpi: sharp on screen and in print, small enough for any phone's canvas. */
export const PAGE = { width: 1240, height: 1754 }
const M = 80 // page margin
const GOLD = '#b37a2c'
const INK = '#1a1714'
const MUTED = '#6e675f'
const LINE = '#e8dfd0'
const SERIF = "'Cormorant Garamond', Georgia, serif"
const SANS = "'Inter Variable', Inter, Arial, sans-serif"
/** Table rows per data page, and entries per "top photos" list. */
export const ROWS_PER_PAGE = 30
const TOP_ROWS = 8

export interface ReportInput {
  eventName: string
  /** Already formatted, e.g. "31 October 2026". */
  dateLabel: string
  studioName: string
  /** The studio logo as a data: URL, or null to show only the name. */
  logo: string | null
  /** Already formatted. */
  generatedOn: string
  data: GuestAnalytics
  /** Thumbnails as data: URLs by photo id; a missing or null entry draws a grey placeholder. */
  thumbs?: Record<string, string | null>
  fonts?: EmbeddedFonts
  measure?: Measure
}

const esc = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const plural = (n: number, word: string) => `${formatCount(n)} ${word}${n === 1 ? '' : 's'}`
const percent = (part: number, whole: number) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : '–')

/** Splits a list into pages of `size` (at least one page, so an empty table still shows its heading). */
export function paginate<T>(rows: T[], size: number): T[][] {
  const pages: T[][] = []
  for (let i = 0; i < rows.length; i += size) pages.push(rows.slice(i, i + size))
  return pages.length ? pages : [[]]
}

/** The same insight lines the panel shows. */
export function insightLines(t: GuestAnalytics['totals']): string[] {
  if (t.people <= 0) return []
  const lines = [
    `${percent(t.downloaders, t.people)} of people saved a photo`,
    `${(t.photoViews / t.people).toFixed(1)} photos viewed per person`,
    `${percent(t.qrScans, t.visits)} of visits came from the QR`,
  ]
  if (t.shares > 0) lines.push(`${formatCount(t.shares)} shares`)
  return lines
}

/** The lists of top photos, in the order and with the counts the panel uses. */
export function topLists(data: GuestAnalytics) {
  return [
    { title: 'Most loved', photos: data.mostLiked ?? data.topPhotos, count: (p: TopPhoto) => plural(p.likes ?? 0, 'like') },
    { title: 'Most downloaded', photos: data.mostDownloaded ?? data.topPhotos, count: (p: TopPhoto) => plural(p.downloads, 'save') },
    { title: 'Most viewed', photos: data.mostViewed ?? data.topPhotos, count: (p: TopPhoto) => plural(p.views, 'view') },
  ]
}

/** "31 Oct, 7 PM" for an hourly row, "31 Oct" for a daily one. */
function rowLabel(unit: 'hour' | 'day', b: Bucket) {
  if (unit === 'day') return b.label
  const [, month, day] = b.key.slice(0, 10).split('-').map(Number)
  const m = new Date(Date.UTC(2000, month - 1, 1)).toLocaleDateString('en-IN', { month: 'short', timeZone: 'UTC' })
  return `${day} ${m}, ${b.label}`
}

/** Largest font size up to `base` at which `text` fits `maxWidth`. */
function fitSize(measure: Measure, text: string, weight: number, family: string, base: number, maxWidth: number) {
  const w = measure(text, `${weight} ${base}px ${family}`)
  return w <= maxWidth ? base : Math.max(12, Math.floor(base * (maxWidth / w)))
}

/** Cuts a line with "…" so it fits `maxWidth`. */
function clip(measure: Measure, text: string, font: string, maxWidth: number) {
  if (measure(text, font) <= maxWidth) return text
  let s = text
  while (s.length > 1 && measure(`${s}…`, font) > maxWidth) s = s.slice(0, -1)
  return `${s}…`
}

const svgOpen = (fonts: EmbeddedFonts) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="${PAGE.width}" height="${PAGE.height}" viewBox="0 0 ${PAGE.width} ${PAGE.height}"><defs><style>${fonts.css}</style></defs><rect width="${PAGE.width}" height="${PAGE.height}" fill="#fff"/>`

const text = (x: number, y: number, size: number, body: string, o: { weight?: number; fill?: string; family?: string; anchor?: 'start' | 'middle' | 'end'; spacing?: number } = {}) =>
  `<text x="${x}" y="${y}" font-family="${o.family ?? SANS}" font-size="${size}" font-weight="${o.weight ?? 400}" fill="${o.fill ?? INK}" text-anchor="${o.anchor ?? 'start'}"${o.spacing ? ` letter-spacing="${o.spacing}"` : ''}>${esc(body)}</text>`

/** Studio logo and name on the left, the report label on the right. */
function header(i: ReportInput, measure: Measure): { svg: string; bottom: number } {
  let x = M
  let svg = ''
  if (i.logo) {
    svg += `<image href="${i.logo}" x="${M}" y="${M - 10}" width="150" height="90" preserveAspectRatio="xMinYMid meet"/>`
    x += 170
  }
  const font = `600 26px ${SANS}`
  svg += text(x, M + 44, 26, clip(measure, i.studioName.toUpperCase(), font, PAGE.width - M - x - 330), { weight: 600, fill: GOLD, spacing: 3 })
  svg += text(PAGE.width - M, M + 36, 22, 'GUEST ACTIVITY REPORT', { anchor: 'end', fill: MUTED, weight: 500, spacing: 3 })
  svg += text(PAGE.width - M, M + 66, 20, `Made on ${i.generatedOn}`, { anchor: 'end', fill: MUTED })
  return { svg: `${svg}<rect x="${M}" y="${M + 100}" width="${PAGE.width - 2 * M}" height="2" fill="${LINE}"/>`, bottom: M + 100 }
}

const footer = (label = 'Live photo gallery by McreatiK') =>
  text(PAGE.width / 2, PAGE.height - 56, 20, label, { anchor: 'middle', fill: MUTED, spacing: 1 })

/** Big numbers: two rows of four tiles. */
function tiles(t: GuestAnalytics['totals'], top: number): { svg: string; bottom: number } {
  const items: [string, number][] = [
    ['People', t.people], ['QR scans', t.qrScans], ['Gallery visits', t.visits], ['Photo views', t.photoViews],
    ['Downloads', t.downloads], ['Likes', t.likes ?? 0], ['Instagram taps', t.instagramClicks], ['Contacted you', t.contacts ?? 0],
  ]
  const gap = 20
  const w = (PAGE.width - 2 * M - 3 * gap) / 4
  const h = 128
  const svg = items.map(([label, value], n) => {
    const x = M + (n % 4) * (w + gap)
    const y = top + Math.floor(n / 4) * (h + gap)
    return `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="18" fill="#fbf6ec" stroke="${LINE}"/>`
      + text(x + 24, y + 70, 54, formatCount(value), { family: SERIF, weight: 600, fill: INK })
      + text(x + 24, y + 106, 21, label, { fill: MUTED })
  }).join('')
  return { svg, bottom: top + 2 * h + gap }
}

/** People per hour (or day) as bars, with a label under every n-th bar. */
function chart(hours: GuestAnalytics['hours'], top: number, height: number): { svg: string; bottom: number } {
  const { unit, items } = buckets(hours)
  if (items.length === 0) return { svg: '', bottom: top }
  const width = PAGE.width - 2 * M
  const max = Math.max(1, ...items.map((b) => b.people))
  const plotTop = top + 50
  const plotH = height - 50 - 36
  const slot = width / items.length
  const bar = Math.min(48, slot * 0.78)
  const every = Math.max(1, Math.ceil(items.length / 10))
  let svg = text(M, top + 24, 26, `People in the gallery, per ${unit}`, { weight: 600 }) + text(PAGE.width - M, top + 24, 20, `peak ${formatCount(max)}`, { anchor: 'end', fill: MUTED })
  svg += `<rect x="${M}" y="${plotTop + plotH}" width="${width}" height="2" fill="${LINE}"/>`
  items.forEach((b, n) => {
    const h = b.people > 0 ? Math.max(4, (b.people / max) * plotH) : 0
    const cx = M + slot * (n + 0.5)
    if (h > 0) svg += `<rect x="${cx - bar / 2}" y="${plotTop + plotH - h}" width="${bar}" height="${h}" rx="4" fill="${GOLD}"/>`
    if (n % every === 0) svg += text(cx, plotTop + plotH + 28, 18, b.label, { anchor: 'middle', fill: MUTED })
  })
  return { svg, bottom: top + height }
}

/** Up to 8 photos in a row with their counts under them. */
function topPhotos(i: ReportInput, top: number): { svg: string; bottom: number } {
  const first = topLists(i.data)[0]
  const photos = (first.photos.length ? first.photos : i.data.topPhotos).slice(0, 8)
  if (photos.length === 0) return { svg: '', bottom: top }
  const gap = 14
  const size = (PAGE.width - 2 * M - 7 * gap) / 8
  let svg = text(M, top + 24, 26, first.photos.length ? 'Guest favourites' : 'Most viewed photos', { weight: 600 })
  photos.forEach((p, n) => {
    const x = M + n * (size + gap)
    const y = top + 50
    const src = i.thumbs?.[p.photoId]
    svg += `<clipPath id="t${n}"><rect x="${x}" y="${y}" width="${size}" height="${size}" rx="12"/></clipPath>`
    svg += src
      ? `<image href="${src}" x="${x}" y="${y}" width="${size}" height="${size}" preserveAspectRatio="xMidYMid slice" clip-path="url(#t${n})"/>`
      : `<rect x="${x}" y="${y}" width="${size}" height="${size}" rx="12" fill="#ece8e1"/>`
    const count = first.photos.length ? first.count(p) : plural(p.views, 'view')
    svg += text(x + size / 2, y + size + 30, 19, count, { anchor: 'middle', fill: MUTED })
  })
  return { svg, bottom: top + 50 + size + 44 }
}

/** The "Find my photos" numbers; null when the event does not have the add-on. */
function faceSearch(f: NonNullable<GuestAnalytics['faceSearch']>, top: number): { svg: string; bottom: number } {
  const sentence = f.searches === 0
    ? 'No guest has used it yet.'
    : `${plural(f.guestsFound, 'guest')} found their photos: ${plural(f.photosFound, 'photo')} found, ${plural(f.downloads, 'download')} from face search.`
  const stats: [string, number][] = [['Searches', f.searches], ['Guests who found them', f.guestsFound], ['Photos found', f.photosFound], ['Downloads', f.downloads], ['Likes', f.likes]]
  const w = (PAGE.width - 2 * M) / stats.length
  let svg = text(M, top + 24, 26, 'Find my photos', { weight: 600 }) + text(M, top + 62, 22, sentence, { fill: MUTED })
  stats.forEach(([label, value], n) => {
    const x = M + n * w
    svg += text(x, top + 130, 44, formatCount(value), { family: SERIF, weight: 600 }) + text(x, top + 160, 17, label, { fill: MUTED })
  })
  return { svg, bottom: top + 180 }
}

/** Page 1: everything a couple wants to see at a glance. */
export function summarySvg(i: ReportInput): string {
  const measure = i.measure ?? measureText
  const fonts = i.fonts ?? { css: '' }
  const { names, occasion } = splitEventName(i.eventName)
  const head = header(i, measure)
  const size = fitSize(measure, names, 600, SERIF, 84, PAGE.width - 2 * M)
  let y = head.bottom + 40 + size
  let svg = svgOpen(fonts) + head.svg + text(M, y, size, names, { family: SERIF, weight: 600 })
  y += 46
  svg += text(M, y, 28, [occasion, i.dateLabel].filter(Boolean).join('  ·  '), { fill: GOLD, weight: 500 })

  const t = tiles(i.data.totals, y + 40)
  svg += t.svg
  y = t.bottom + 44
  const lines = insightLines(i.data.totals)
  if (lines.length) {
    // Two columns, so the block stays short.
    lines.forEach((line, n) => {
      const [value, ...rest] = line.split(' ')
      const x = M + (n % 2) * ((PAGE.width - 2 * M) / 2)
      const ly = y + Math.floor(n / 2) * 40
      svg += `<text x="${x}" y="${ly}" font-family="${SANS}" font-size="24" fill="${MUTED}"><tspan font-weight="700" fill="${INK}">${esc(value)}</tspan> ${esc(rest.join(' '))}</text>`
    })
    y += Math.ceil(lines.length / 2) * 40 + 20
  } else {
    svg += text(M, y, 24, 'No guest activity yet.', { fill: MUTED })
    y += 60
  }
  const c = chart(i.data.hours, y, 300)
  svg += c.svg
  y = c.bottom + 40
  const p = topPhotos(i, y)
  svg += p.svg
  y = p.bottom + 40
  if (i.data.faceSearch) svg += faceSearch(i.data.faceSearch, y).svg
  return `${svg}${footer()}</svg>`
}

/** A heading band for the data pages. */
function dataHead(i: ReportInput, title: string, page: number, pages: number, measure: Measure) {
  const nameFont = `600 40px ${SERIF}`
  return text(M, M + 40, 40, clip(measure, i.eventName.replace(/_/g, ' '), nameFont, PAGE.width - 2 * M - 300), { family: SERIF, weight: 600 })
    + text(PAGE.width - M, M + 36, 22, `Page ${page} of ${pages}`, { anchor: 'end', fill: MUTED })
    + text(M, M + 84, 26, title, { fill: GOLD, weight: 600 })
    + `<rect x="${M}" y="${M + 108}" width="${PAGE.width - 2 * M}" height="2" fill="${LINE}"/>`
}

/** The full hour-by-hour (or day-by-day) numbers, spread over as many pages as needed. */
function tablePages(i: ReportInput, firstPage: number, totalPages: number, measure: Measure): string[] {
  const { unit, items } = buckets(i.data.hours)
  const chunks = paginate(items, ROWS_PER_PAGE)
  const cols = [M, 560, 780, 1000]
  return chunks.map((rows, n) => {
    let svg = svgOpen(i.fonts ?? { css: '' }) + dataHead(i, `Activity by ${unit}${chunks.length > 1 ? ` (${n + 1}/${chunks.length})` : ''}`, firstPage + n, totalPages, measure)
    let y = M + 170
    ;[unit === 'hour' ? 'Hour' : 'Day', 'People', 'Photo views', 'Downloads'].forEach((h, c) => {
      svg += text(cols[c], y, 20, h.toUpperCase(), { fill: MUTED, weight: 600, spacing: 2 })
    })
    y += 18
    if (rows.length === 0) svg += text(M, y + 50, 24, 'No guest activity yet.', { fill: MUTED })
    rows.forEach((b, r) => {
      const ry = y + 46 + r * 46
      svg += `<rect x="${M}" y="${ry - 34}" width="${PAGE.width - 2 * M}" height="1.5" fill="${LINE}"/>`
        + text(cols[0], ry, 24, rowLabel(unit, b)) + text(cols[1], ry, 24, formatCount(b.people)) + text(cols[2], ry, 24, formatCount(b.photoViews)) + text(cols[3], ry, 24, formatCount(b.downloads))
    })
    return `${svg}${footer()}</svg>`
  })
}

/** The last data page: the three top-photo lists with counts, and the face-search numbers. */
function topPage(i: ReportInput, page: number, totalPages: number, measure: Measure): string | null {
  const lists = topLists(i.data).filter((l) => l.photos.length > 0)
  if (lists.length === 0 && !i.data.faceSearch) return null
  let svg = svgOpen(i.fonts ?? { css: '' }) + dataHead(i, 'Top photos and face search', page, totalPages, measure)
  let y = M + 170
  const font = `400 24px ${SANS}`
  for (const l of lists) {
    svg += text(M, y, 26, l.title, { weight: 600 })
    l.photos.slice(0, TOP_ROWS).forEach((p, n) => {
      const ry = y + 44 + n * 40
      svg += text(M, ry, 24, `${n + 1}. ${clip(measure, p.fileName, font, 640)}`) + text(PAGE.width - M, ry, 24, l.count(p), { anchor: 'end', fill: MUTED })
    })
    y += 44 + Math.min(l.photos.length, TOP_ROWS) * 40 + 36
  }
  if (i.data.faceSearch) svg += faceSearch(i.data.faceSearch, y).svg
  return `${svg}${footer()}</svg>`
}

/** All pages of the PDF: the summary, then the table page(s), then the top photos page. */
export function reportSvgs(i: ReportInput): string[] {
  const measure = i.measure ?? measureText
  const tableCount = paginate(buckets(i.data.hours).items, ROWS_PER_PAGE).length
  const hasTop = topLists(i.data).some((l) => l.photos.length > 0) || !!i.data.faceSearch
  const total = 1 + tableCount + (hasTop ? 1 : 0)
  const pages = [summarySvg(i), ...tablePages(i, 2, total, measure)]
  const last = topPage(i, 2 + tableCount, total, measure)
  if (last) pages.push(last)
  return pages
}

// ---- Browser only: fetching pictures, painting pages and saving files ----

/** Draws an SVG page on a canvas and returns it as a JPEG. */
export async function svgToJpeg(svg: string): Promise<{ blob: Blob; bytes: Uint8Array }> {
  const url = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }))
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    const canvas = document.createElement('canvas')
    canvas.width = PAGE.width
    canvas.height = PAGE.height
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('This browser cannot make the report. Try Chrome or Edge.')
    ctx.fillStyle = '#fff'
    ctx.fillRect(0, 0, PAGE.width, PAGE.height)
    ctx.drawImage(img, 0, 0, PAGE.width, PAGE.height)
    const blob = await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not make the picture'))), 'image/jpeg', 0.92))
    canvas.width = 0
    canvas.height = 0
    return { blob, bytes: new Uint8Array(await blob.arrayBuffer()) }
  } finally {
    URL.revokeObjectURL(url)
  }
}

/** A thumbnail as a data: URL (fetched with CORS so the canvas stays clean); null if it can't be loaded. */
async function thumbData(url: string | null): Promise<string | null> {
  if (!url) return null
  try {
    const res = await fetch(withDownloadParam(url), { mode: 'cors', credentials: 'omit' })
    return res.ok ? await dataUrl(await res.blob()) : null
  } catch {
    return null
  }
}

export type ReportFormat = 'image' | 'pdf'

export interface ReportRequest {
  eventId: string
  eventName: string
  dateLabel: string
  studioName: string
  data: GuestAnalytics
  format: ReportFormat
}

/** Builds the report and saves it as `<event>-guest-report.jpg` or `.pdf`. */
export async function generateReport(req: ReportRequest): Promise<void> {
  const { data } = req
  const first = topLists(data)[0]
  const shown = (first.photos.length ? first.photos : data.topPhotos).slice(0, 8)
  const [fonts, logo, thumbs] = await Promise.all([
    embeddedFonts(),
    // No logo is normal (a 404): the page then shows just the studio name.
    adminApi.eventLogo(req.eventId).then(dataUrl).catch(() => null),
    Promise.all(shown.map(async (p) => [p.photoId, await thumbData(p.thumbnailUrl)] as const)).then(Object.fromEntries),
  ])
  const input: ReportInput = {
    eventName: req.eventName,
    dateLabel: req.dateLabel,
    studioName: req.studioName,
    logo,
    generatedOn: new Date().toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' }),
    data,
    thumbs,
    fonts,
  }
  const name = `${fileSlug(req.eventName)}-guest-report`
  if (req.format === 'image') {
    downloadBlob((await svgToJpeg(summarySvg(input))).blob, `${name}.jpg`)
    return
  }
  const pages: PdfPage[] = []
  for (const svg of reportSvgs(input)) pages.push({ bytes: (await svgToJpeg(svg)).bytes, ...PAGE })
  downloadBlob(jpegPagesToPdf(pages), `${name}.pdf`)
}
