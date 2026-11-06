import { describe, expect, it } from 'vitest'
import { BLEED, BOARD, boardSvg, CARD, cardSvg, fileSlug, isBoardTheme, withPrintDpi, type Measure } from './printDesign'

// A fixed-width "font": every character is 0.5 em wide.
const measure: Measure = (text, font) => text.length * Number(/(\d+(?:\.\d+)?)px/.exec(font)![1]) * 0.5
const qr = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 29 29"><path d="M0 0h7v7H0z"/></svg>'
const card = { names: 'Arun & Priya', detail: 'WEDDING · 3 NOVEMBER 2026', qr }
const brand = { name: 'Lens & Light Studio', logo: null, logoScale: 100, instagram: 'lensandlight.studio' }

function attr(svg: string, name: string) {
  return Number(new RegExp(`<svg[^>]* ${name}="([\\d.]+)"`).exec(svg)![1])
}

/** x / y of every <text>, in trim coordinates (the artwork is drawn inside translate(bleed)). */
function textPositions(svg: string) {
  return [...svg.matchAll(/<text x="([\d.]+)" y="([\d.]+)"/g)].map((m) => ({ x: Number(m[1]), y: Number(m[2]) }))
}

describe('print designs', () => {
  it('makes print-size files with a 0.125 in bleed at 300 dpi', () => {
    expect(BOARD.width).toBe(5475)
    expect(BOARD.height).toBe(7275)
    expect(CARD.width).toBe(2475)
    expect(CARD.height).toBe(3675)
    const board = boardSvg('cream', brand, { measure })
    expect(attr(board, 'width')).toBe(5475)
    expect(attr(board, 'height')).toBe(7275)
    expect(board).toContain(`translate(${BLEED} ${BLEED})`)
    const event = cardSvg('dark', card, { table: false, measure })
    expect(attr(event, 'width')).toBe(2475)
    expect(attr(event, 'height')).toBe(3675)
  })

  it('keeps all board text inside the 0.5 in safe zone', () => {
    const safe = 0.5 * 300
    for (const { x, y } of textPositions(boardSvg('white', brand, { measure }))) {
      expect(x).toBeGreaterThanOrEqual(safe)
      expect(x).toBeLessThanOrEqual(18 * 300 - safe)
      expect(y).toBeGreaterThanOrEqual(safe)
      expect(y).toBeLessThanOrEqual(24 * 300 - safe)
    }
  })

  it('prints the contact number above the Instagram handle on the board', () => {
    const board = boardSvg('cream', { ...brand, phone: '+919876543210' }, { measure })
    expect(board).toContain('+91 98765 43210')
    expect(board.indexOf('+91 98765 43210')).toBeLessThan(board.indexOf('@lensandlight.studio'))
    expect(boardSvg('cream', brand, { measure })).not.toContain('+91')
  })

  it('puts names, date and QR on the card, and the scan line only on the table card', () => {
    const event = cardSvg('cream', card, { table: false, measure })
    const table = cardSvg('cream', card, { table: true, measure })
    expect(event).toContain('Arun &amp; Priya')
    expect(event).toContain('WEDDING · 3 NOVEMBER 2026')
    expect(event).toContain('<path d="M0 0h7v7H0z"/>')
    expect(event).not.toContain('Scan to see the photos live')
    expect(table).toContain('Scan to see the photos live')
    // The board carries the generic lines and the studio's Instagram; the card in its frame shows only when previewed.
    const board = boardSvg('dark', brand, { measure })
    expect(board).toContain('Scan to see the photos live')
    expect(board).toContain('POWERED BY MCREATIK INSTANT MEMORIES')
    expect(board).toContain('@lensandlight.studio')
    expect(board).not.toContain('Arun')
    expect(boardSvg('dark', brand, { measure, card })).toContain('Arun &amp; Priya')
  })

  it('shrinks or splits long names so they fit the card', () => {
    const long = cardSvg('cream', { ...card, names: 'Venkataraghavan Subramanian & Lakshmipriya Ramachandran' }, { table: false, measure })
    const lines = [...long.matchAll(/font-weight="600" font-size="([\d.]+)"[^>]*>([^<]+)</g)]
    expect(lines.length).toBe(2)
    for (const [, size, text] of lines) expect(measure(text.replace(/&amp;/g, '&'), `${size}px`)).toBeLessThanOrEqual(8 * 300 - 2 * 0.45 * 300)
  })

  it('escapes text', () => {
    expect(cardSvg('white', { ...card, names: 'A <b> & "C"' }, { table: false, measure })).toContain('A &lt;b&gt; &amp; &quot;C&quot;')
  })

  it('marks the JPEG as 300 dpi', () => {
    const jfif = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 16, 0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0])
    const out = withPrintDpi(jfif)
    expect([...out.slice(13, 18)]).toEqual([1, 1, 44, 1, 44])
    const other = new Uint8Array([0xff, 0xd8, 0xff, 0xe1, 0, 0])
    expect(withPrintDpi(other)).toBe(other)
  })

  it('knows the three designs and makes file names', () => {
    expect(['cream', 'dark', 'white'].every(isBoardTheme)).toBe(true)
    expect(isBoardTheme('neon')).toBe(false)
    expect(fileSlug('Arun & Priya')).toBe('arun-priya')
  })
})
