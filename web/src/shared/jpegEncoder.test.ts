import { describe, expect, it } from 'vitest'
import { jpegStripEncoder } from './jpegEncoder'

async function bytes(blob: Blob) {
  return new Uint8Array(await blob.arrayBuffer())
}

describe('Strip JPEG encoder (full-size print files on phones)', () => {
  it('writes a baseline JPEG with the right size and 300 ppi, from strips', async () => {
    const width = 20
    const height = 21 // not a multiple of 8: the edge blocks are padded
    const enc = jpegStripEncoder(width, height)
    const strip = (rows: number, shade: number) => {
      const d = new Uint8ClampedArray(width * rows * 4)
      for (let i = 0; i < d.length; i += 4) { d[i] = shade; d[i + 1] = 255 - shade; d[i + 2] = 40; d[i + 3] = 255 }
      return d
    }
    enc.addStrip(strip(16, 200), 16)
    enc.addStrip(strip(5, 20), 5)
    const b = await bytes(enc.finish())
    expect([b[0], b[1]]).toEqual([0xff, 0xd8]) // SOI
    expect(String.fromCharCode(b[6], b[7], b[8], b[9])).toBe('JFIF')
    expect(b[13]).toBe(1) // pixels per inch
    expect((b[14] << 8) | b[15]).toBe(300)
    expect((b[16] << 8) | b[17]).toBe(300)
    const sof = b.findIndex((v, i) => v === 0xff && b[i + 1] === 0xc0)
    expect((b[sof + 5] << 8) | b[sof + 6]).toBe(height)
    expect((b[sof + 7] << 8) | b[sof + 8]).toBe(width)
    expect([b[b.length - 2], b[b.length - 1]]).toEqual([0xff, 0xd9]) // EOI
  })

  it('refuses a strip that is not a multiple of 8 rows (except the last) and a short picture', () => {
    const enc = jpegStripEncoder(8, 32)
    expect(() => enc.addStrip(new Uint8ClampedArray(8 * 5 * 4), 5)).toThrow(/multiple of 8/)
    enc.addStrip(new Uint8ClampedArray(8 * 16 * 4), 16)
    expect(() => enc.finish()).toThrow(/16 of 32 rows/)
  })
})
