/**
 * A quick look at a guest's selfie before it is sent (and a paid search is made): is it too dark, too bright, blurry
 * or tiny? No AI model: just the brightness, the sharpness (variance of the Laplacian) and the size of the picture,
 * measured on a small copy. The limits are deliberately lenient: a usable selfie must never be blocked, and the
 * server still decides whether there is a face (NO_FACE).
 */
export type SelfieIssue = 'dark' | 'bright' | 'blurry' | 'small'

/** Mean brightness (0 to 255) below / above which the selfie is too dark / too bright. */
export const DARK_BELOW = 45
export const BRIGHT_ABOVE = 225
/** Laplacian variance below this is blurry (a sharp photo is in the hundreds; a flat wall around 20 to 60). */
export const BLURRY_BELOW = 12
/** The shorter side, in pixels, below this is too small. */
export const MIN_SHORT_SIDE = 240
/** The measuring copy is at most this many pixels on the long edge. */
export const SAMPLE_EDGE = 320

export interface SelfieCheck {
  ok: boolean
  issues: SelfieIssue[]
}

/** The tip shown for each problem. */
export const SELFIE_TIPS: Record<SelfieIssue, string> = {
  dark: 'Move to better light, with the light in front of you.',
  bright: 'Too bright. Step out of direct sun or a glaring light.',
  blurry: 'Hold the phone still, then take it again.',
  small: 'Come closer so your face fills the picture.',
}

/** Judges RGBA pixels (as from a canvas) of a picture that was {@code fullWidth} x {@code fullHeight} before shrinking. */
export function analyzePixels(rgba: ArrayLike<number>, width: number, height: number, fullWidth = width, fullHeight = height): SelfieCheck {
  const issues: SelfieIssue[] = []
  if (Math.min(fullWidth, fullHeight) < MIN_SHORT_SIDE) issues.push('small')
  const n = width * height
  if (n > 0) {
    const gray = new Float32Array(n)
    let sum = 0
    for (let i = 0; i < n; i++) {
      const g = 0.299 * rgba[i * 4] + 0.587 * rgba[i * 4 + 1] + 0.114 * rgba[i * 4 + 2]
      gray[i] = g
      sum += g
    }
    const mean = sum / n
    const tooDark = mean < DARK_BELOW
    const tooBright = mean > BRIGHT_ABOVE
    if (tooDark) issues.push('dark')
    if (tooBright) issues.push('bright')
    // A very dark or very bright picture is flat too; its tip is the light, not the shaking.
    if (!tooDark && !tooBright && width > 2 && height > 2 && laplacianVariance(gray, width, height) < BLURRY_BELOW) issues.push('blurry')
  }
  return { ok: issues.length === 0, issues }
}

function laplacianVariance(gray: Float32Array, w: number, h: number): number {
  let sum = 0
  let sumSq = 0
  let count = 0
  for (let y = 1; y < h - 1; y++) {
    for (let x = 1; x < w - 1; x++) {
      const i = y * w + x
      const v = gray[i - w] + gray[i + w] + gray[i - 1] + gray[i + 1] - 4 * gray[i]
      sum += v
      sumSq += v * v
      count++
    }
  }
  if (count === 0) return Infinity
  const mean = sum / count
  return sumSq / count - mean * mean
}

/**
 * Checks the (already resized) selfie. If this browser cannot do the measuring, the selfie passes: the check only ever
 * helps, it never blocks.
 */
export async function checkSelfie(selfie: Blob): Promise<SelfieCheck> {
  try {
    const bitmap = await createImageBitmap(selfie)
    try {
      const scale = Math.min(1, SAMPLE_EDGE / Math.max(bitmap.width, bitmap.height))
      const w = Math.max(1, Math.round(bitmap.width * scale))
      const h = Math.max(1, Math.round(bitmap.height * scale))
      const canvas = document.createElement('canvas')
      canvas.width = w
      canvas.height = h
      const ctx = canvas.getContext('2d', { willReadFrequently: true })
      if (!ctx) return { ok: true, issues: [] }
      ctx.drawImage(bitmap, 0, 0, w, h)
      return analyzePixels(ctx.getImageData(0, 0, w, h).data, w, h, bitmap.width, bitmap.height)
    } finally {
      bitmap.close()
    }
  } catch {
    return { ok: true, issues: [] }
  }
}
