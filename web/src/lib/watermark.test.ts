import { describe, expect, it, vi } from 'vitest'
import { bandHeight, CORNER_LOGO, DEFAULT_MARK, drawMark, HEIGHT_MEDIUM, HEIGHT_SMALL, markedSize, MARGIN, MAX_WIDTH, resolveContent, STRIP_COLOR, STRIP_LINE } from './watermark'
import type { LogoArt, Mark } from './watermark'
import type { MarkSettings } from './types'

/** A fake 2D context that records what was drawn, and with which style. */
function fakeCtx() {
  const calls: { image?: number[]; imageSrc?: unknown; text?: string; textPos?: number[]; fonts: string[]; rects: { color: string; r: number[] }[]; clipped: boolean; shadowBlur?: number; fill?: string } = {
    fonts: [], rects: [], clipped: false,
  }
  const ctx = {
    save: vi.fn(), restore: vi.fn(), beginPath: vi.fn(), clip: vi.fn(() => { calls.clipped = true }),
    roundRect: vi.fn(), rect: vi.fn(),
    fillRect: vi.fn((...r: number[]) => { calls.rects.push({ color: ctx.fillStyle, r }) }),
    drawImage: vi.fn((src: unknown, ...a: number[]) => { calls.image = a; calls.imageSrc = src }),
    fillText: vi.fn((t: string, ...pos: number[]) => { calls.text = t; calls.textPos = pos; calls.shadowBlur = ctx.shadowBlur; calls.fill = ctx.fillStyle }),
    measureText: vi.fn((t: string) => ({ width: t.length * 10 })),
    _font: '',
    set font(f: string) { ctx._font = f; calls.fonts.push(f) },
    get font() { return ctx._font },
    fillStyle: '', globalAlpha: 1, textBaseline: '', shadowColor: '', shadowBlur: 0,
  }
  return { ctx: ctx as unknown as CanvasRenderingContext2D, calls }
}

const settings = (s: Partial<MarkSettings> = {}): MarkSettings => ({ ...DEFAULT_MARK, ...s })
const art = (w: number, h: number, white = false): LogoArt => ({ width: w, height: h, image: { tag: 'img' } as unknown as CanvasImageSource, white: white ? ({ tag: 'white' } as unknown as CanvasImageSource) : undefined })
const mark = (m: Partial<Mark> = {}): Mark => ({ logo: null, name: 'Lens Studio', instagram: null, phone: null, logoScale: 100, settings: settings({ logo: false, name: true, instagram: false }), ...m })

describe('corner mark', () => {
  it('sits bottom-right inside the margin, white with a soft shadow, no box', () => {
    const { ctx, calls } = fakeCtx()
    drawMark(ctx, 2048, 1365, mark())
    const margin = Math.round(1365 * MARGIN)
    const [x, y] = calls.textPos!
    const width = 'Lens Studio'.length * 10
    expect(x + width).toBeCloseTo(2048 - margin, 0)
    const lineH = 1365 * HEIGHT_SMALL * 0.75
    expect(y).toBeCloseTo(1365 - margin - lineH / 2, 0)
    expect(calls.text).toBe('Lens Studio')
    expect(calls.fill).toBe('#fff')
    expect(calls.shadowBlur).toBeCloseTo(lineH * 0.25, 1)
    expect(calls.rects).toHaveLength(0) // no label box
    expect(calls.fonts.at(-1)).toMatch(/^600 .*Inter Variable/)
  })

  it('follows the position and size settings', () => {
    const left = fakeCtx()
    drawMark(left.ctx, 1000, 1000, mark({ settings: settings({ logo: false, name: true, position: 'LEFT' }) }))
    expect(left.calls.textPos![0]).toBe(Math.round(1000 * MARGIN))
    const centre = fakeCtx()
    drawMark(centre.ctx, 1000, 1000, mark({ settings: settings({ logo: false, name: true, position: 'CENTER' }) }))
    expect(centre.calls.textPos![0] + 'Lens Studio'.length * 10 / 2).toBeCloseTo(500, 0)
    const small = fakeCtx()
    const medium = fakeCtx()
    drawMark(small.ctx, 1000, 1000, mark())
    drawMark(medium.ctx, 1000, 1000, mark({ settings: settings({ logo: false, name: true, size: 'MEDIUM' }) }))
    expect(parseFloat(medium.calls.fonts.at(-1)!.split(' ')[1])).toBeCloseTo(1000 * HEIGHT_MEDIUM * 0.75, 1)
    expect(parseFloat(small.calls.fonts.at(-1)!.split(' ')[1])).toBeCloseTo(1000 * HEIGHT_SMALL * 0.75, 1)
  })

  it('joins the details with a dot and formats the phone', () => {
    const { calls, ctx } = fakeCtx()
    drawMark(ctx, 2000, 1000, mark({ instagram: 'lens.in', phone: '+919876543210', settings: settings({ logo: false, name: true, instagram: true, phone: true }) }))
    expect(calls.text).toBe('Lens Studio  ·  @lens.in  ·  +91 98765 43210')
  })

  it('shows only ticked details the studio has, and falls back to the name', () => {
    expect(resolveContent(mark({ settings: settings({ logo: true, name: false, instagram: true, phone: true }) })).texts).toEqual(['Lens Studio'])
    expect(resolveContent(mark({ instagram: 'lens', settings: settings({ logo: false, name: false, instagram: true }) })).texts).toEqual(['@lens'])
    const withLogo = resolveContent(mark({ logo: art(300, 100), settings: settings({ name: false, instagram: false }) }))
    expect(withLogo.logo).not.toBeNull()
    expect(withLogo.texts).toEqual([])
    // Logo ticked but missing, nothing else: the name.
    expect(resolveContent(mark({ settings: settings({ logo: true, name: false, instagram: true }) })).texts).toEqual(['Lens Studio'])
  })

  it('never covers more than 60% of the width: long marks scale down', () => {
    const { ctx, calls } = fakeCtx()
    drawMark(ctx, 1000, 1000, mark({ name: 'x'.repeat(100) }))
    const px = parseFloat(calls.fonts.at(-1)!.split(' ')[1])
    expect(px).toBeLessThan(1000 * HEIGHT_SMALL * 0.75)
    // The fake measures 10 px a letter whatever the size: 1000 px unscaled, so it was scaled by 0.6.
    expect(px).toBeCloseTo(1000 * HEIGHT_SMALL * 0.75 * MAX_WIDTH, 1)
  })

  it('draws a transparent logo as a white silhouette, an opaque one as is with rounded corners', () => {
    const cut = fakeCtx()
    const logo = art(300, 100, true)
    drawMark(cut.ctx, 2000, 1000, mark({ logo, settings: settings({ name: false, instagram: false }) }))
    expect(cut.calls.imageSrc).toBe(logo.white)
    expect(cut.calls.clipped).toBe(false)
    const opaque = fakeCtx()
    const flat = art(300, 100)
    drawMark(opaque.ctx, 2000, 1000, mark({ logo: flat, settings: settings({ name: false, instagram: false }) }))
    expect(opaque.calls.imageSrc).toBe(flat.image)
    expect(opaque.calls.clipped).toBe(true)
    // Logo height: 4.2 % of the shorter side times the logo size.
    expect(opaque.calls.image![3]).toBeCloseTo(1000 * HEIGHT_SMALL * CORNER_LOGO, 1)
  })

  it('scales the logo with the studio logo size', () => {
    const { ctx, calls } = fakeCtx()
    drawMark(ctx, 2000, 1000, mark({ logo: art(300, 100), logoScale: 200, settings: settings({ name: false, instagram: false }) }))
    expect(calls.image![3]).toBeCloseTo(1000 * HEIGHT_SMALL * CORNER_LOGO * 2, 1)
  })
})

describe('signature strip', () => {
  const strip = settings({ style: 'STRIP', position: 'CENTER', logo: true, name: true, instagram: true, phone: false })

  it('makes the image taller by the band and leaves the photo alone', () => {
    expect(bandHeight(2048)).toBe(Math.round(2048 * 0.06))
    expect(bandHeight(400)).toBe(40)
    expect(markedSize(2048, 1365, strip)).toEqual({ width: 2048, height: 1365 + bandHeight(2048) })
    expect(markedSize(2048, 1365, settings())).toEqual({ width: 2048, height: 1365 })
  })

  it('paints the band with a line on top and the details centred inside it', () => {
    const { ctx, calls } = fakeCtx()
    const logo = art(300, 100)
    drawMark(ctx, 2048, 1365, mark({ logo, instagram: 'lens', settings: strip }))
    const band = bandHeight(2048)
    expect(calls.rects[0]).toEqual({ color: STRIP_COLOR, r: [0, 1365, 2048, band] })
    expect(calls.rects[1]).toEqual({ color: STRIP_LINE, r: [0, 1365, 2048, 1] })
    expect(calls.text).toBe('Lens Studio  ·  @lens')
    expect(calls.fill).toBe('#2A2520')
    const [, y, , h] = calls.image!
    expect(h).toBeCloseTo(band * 0.6, 1)
    expect(y + h / 2).toBeCloseTo(1365 + band / 2, 1)
    expect(calls.imageSrc).toBe(logo.image) // original colours
  })

  it('aligns left, centre or right and scales down to fit', () => {
    const left = fakeCtx()
    drawMark(left.ctx, 1000, 800, mark({ settings: { ...strip, position: 'LEFT', logo: false } }))
    expect(left.calls.textPos![0]).toBeCloseTo(30, 1)
    const right = fakeCtx()
    drawMark(right.ctx, 1000, 800, mark({ settings: { ...strip, position: 'RIGHT', logo: false } }))
    expect(right.calls.textPos![0] + 'Lens Studio'.length * 10).toBeCloseTo(970, 1)
    const long = fakeCtx()
    drawMark(long.ctx, 1000, 800, mark({ name: 'x'.repeat(200), settings: { ...strip, logo: false } }))
    expect(long.calls.text!.length * 10).toBeGreaterThan(940) // measured at the unscaled size...
    expect(parseFloat(long.calls.fonts.at(-1)!.split(' ')[1])).toBeLessThan(bandHeight(1000) * 0.36) // ...so it was shrunk
  })
})
