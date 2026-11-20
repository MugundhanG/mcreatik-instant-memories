import { describe, expect, it, vi } from 'vitest'
import { drawMark, MARGIN, MAX_WIDTH } from './watermark'

/** A fake 2D context that records the label box and what was drawn in it. */
function fakeCtx() {
  const calls: { rect?: number[]; image?: number[]; text?: string } = {}
  const ctx = {
    save: vi.fn(), restore: vi.fn(), beginPath: vi.fn(), fill: vi.fn(),
    roundRect: vi.fn((...a: number[]) => { calls.rect = a.slice(0, 4) }),
    rect: vi.fn(),
    drawImage: vi.fn((_img: unknown, ...a: number[]) => { calls.image = a }),
    fillText: vi.fn((t: string) => { calls.text = t }),
    measureText: vi.fn((t: string) => ({ width: t.length * 10 })),
    font: '', fillStyle: '', globalAlpha: 1, textBaseline: '',
  }
  return { ctx: ctx as unknown as CanvasRenderingContext2D, calls }
}

describe('watermark label', () => {
  it('sits in the bottom-right corner, inside the margin', () => {
    const { ctx, calls } = fakeCtx()
    drawMark(ctx, 2048, 1365, { logo: null, name: 'Lens Studio', logoScale: 100 })
    const [x, y, w, h] = calls.rect!
    const margin = Math.round(1365 * MARGIN)
    expect(x + w).toBe(2048 - margin)
    expect(y + h).toBe(1365 - margin)
    expect(calls.text).toBe('Lens Studio')
  })

  it('draws the logo, and a very wide one never covers more than its share of the width', () => {
    const { ctx, calls } = fakeCtx()
    const logo = { width: 2000, height: 100 } as unknown as ImageBitmap
    drawMark(ctx, 1000, 1000, { logo, name: '', logoScale: 200 })
    const [, , w] = calls.rect!
    expect(w).toBeLessThanOrEqual(Math.floor(1000 * MAX_WIDTH))
    expect(calls.image).toBeDefined()
  })
})
