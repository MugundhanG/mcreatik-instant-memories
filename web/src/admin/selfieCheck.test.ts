import { describe, expect, it } from 'vitest'
import { analyzePixels, BLURRY_BELOW, SELFIE_TIPS } from './selfieCheck'

/** RGBA pixels from a function of (x, y) giving a gray level. */
function image(w: number, h: number, level: (x: number, y: number) => number) {
  const data = new Uint8ClampedArray(w * h * 4)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4
      data[i] = data[i + 1] = data[i + 2] = level(x, y)
      data[i + 3] = 255
    }
  }
  return data
}

/** A sharp-looking picture: mid brightness with strong edges (a checkerboard of 8 px squares). */
const sharp = (x: number, y: number) => ((Math.floor(x / 8) + Math.floor(y / 8)) % 2 === 0 ? 70 : 190)
const W = 200
const H = 200

describe('selfie quality check', () => {
  it('passes a normal, sharp, big enough selfie', () => {
    expect(analyzePixels(image(W, H, sharp), W, H, 1024, 768)).toEqual({ ok: true, issues: [] })
  })

  it('flags a dark selfie and a bright one, with the light as the only tip', () => {
    expect(analyzePixels(image(W, H, (x, y) => sharp(x, y) / 5), W, H, 1024, 768).issues).toEqual(['dark'])
    expect(analyzePixels(image(W, H, (x, y) => 215 + sharp(x, y) / 10), W, H, 1024, 768).issues).toEqual(['bright'])
  })

  it('flags a blurry selfie (a smooth gradient has almost no edges)', () => {
    const result = analyzePixels(image(W, H, (x) => 100 + x / 4), W, H, 1024, 768)
    expect(result.issues).toEqual(['blurry'])
    expect(BLURRY_BELOW).toBeLessThan(30) // lenient on purpose
  })

  it('flags a small picture by its shorter side, measured on the original size', () => {
    expect(analyzePixels(image(W, H, sharp), W, H, 200, 300).issues).toEqual(['small'])
    expect(analyzePixels(image(W, H, sharp), W, H, 300, 200).issues).toEqual(['small'])
    expect(analyzePixels(image(W, H, sharp), W, H, 480, 640).ok).toBe(true)
  })

  it('is lenient: a slightly dim or soft selfie still passes', () => {
    expect(analyzePixels(image(W, H, (x, y) => sharp(x, y) * 0.6), W, H, 1024, 768).ok).toBe(true) // mean about 78
    expect(analyzePixels(image(W, H, (x, y) => 120 + (sharp(x, y) - 130) / 8), W, H, 1024, 768).ok).toBe(true) // soft, still some detail
  })

  it('has a tip for every issue', () => {
    expect(Object.keys(SELFIE_TIPS).sort()).toEqual(['blurry', 'bright', 'dark', 'small'])
  })
})
