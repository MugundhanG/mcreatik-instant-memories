/**
 * Turns a photo the admin picked into what the dashboard upload sends: the gallery (web) and thumbnail JPEGs,
 * made in the browser with the same sizes and quality as the camera uploader, plus the original's details.
 * The original never leaves the computer, so 2,000 photos upload in minutes and the small server does no work.
 */

export const WEB_LONG_EDGE = 2048
export const WEB_QUALITY = 0.85
export const THUMB_LONG_EDGE = 640
export const THUMB_QUALITY = 0.78
export const ACCEPTED_TYPES = ['image/jpeg', 'image/png']

export interface PreparedPhoto {
  web: Blob
  thumbnail: Blob
  /** The original's displayed size (after EXIF rotation). */
  width: number
  height: number
  capturedAt: string | null
  checksumSha256: string
}

export async function sha256Hex(data: ArrayBuffer) {
  const digest = await crypto.subtle.digest('SHA-256', data)
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

/**
 * EXIF DateTimeOriginal ("2026:10:10 08:15:00") as an ISO instant. Like the camera uploader, the camera's local
 * clock time is stored as if it were UTC, so day filters show the day printed on the camera.
 */
export function exifCapturedAt(buf: ArrayBuffer): string | null {
  const v = new DataView(buf)
  if (v.byteLength < 4 || v.getUint16(0) !== 0xffd8) return null
  let off = 2
  while (off + 4 <= v.byteLength) {
    const marker = v.getUint16(off)
    const len = v.getUint16(off + 2)
    if ((marker & 0xff00) !== 0xff00 || marker === 0xffda) return null // not a marker / image data starts
    if (marker === 0xffe1 && off + 10 <= v.byteLength && v.getUint32(off + 4) === 0x45786966) {
      return readExifDate(v, off + 10)
    }
    off += 2 + len
  }
  return null
}

function readExifDate(v: DataView, tiff: number): string | null {
  try {
    const le = v.getUint16(tiff) === 0x4949
    const u16 = (o: number) => v.getUint16(tiff + o, le)
    const u32 = (o: number) => v.getUint32(tiff + o, le)
    /** Value offset of `tag` in the IFD at `ifd`, or -1. */
    const find = (ifd: number, tag: number) => {
      const n = u16(ifd)
      for (let i = 0; i < n; i++) {
        const e = ifd + 2 + i * 12
        if (u16(e) === tag) return e
      }
      return -1
    }
    const ascii = (entry: number) => {
      const count = u32(entry + 4)
      const at = count > 4 ? u32(entry + 8) : entry + 8
      let s = ''
      for (let i = 0; i < Math.min(count, 19); i++) s += String.fromCharCode(v.getUint8(tiff + at + i))
      return s
    }
    const ifd0 = u32(4)
    let entry = -1
    const exifPtr = find(ifd0, 0x8769)
    if (exifPtr >= 0) entry = find(u32(exifPtr + 8), 0x9003) // DateTimeOriginal
    if (entry < 0) entry = find(ifd0, 0x0132) // DateTime
    if (entry < 0) return null
    const m = ascii(entry).match(/^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})$/)
    if (!m || m[1] === '0000') return null
    return `${m[1]}-${m[2]}-${m[3]}T${m[4]}:${m[5]}:${m[6]}Z`
  } catch {
    return null // metadata is optional: never fail a photo because of it
  }
}

/** Long edge scaled down to `max` (never up). */
export function fit(width: number, height: number, max: number) {
  const scale = Math.min(1, max / Math.max(width, height))
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) }
}

function encode(source: CanvasImageSource, width: number, height: number, max: number, quality: number) {
  const size = fit(width, height, max)
  const canvas = document.createElement('canvas')
  canvas.width = size.width
  canvas.height = size.height
  const ctx = canvas.getContext('2d')
  if (!ctx) throw new Error('This browser cannot resize photos')
  ctx.imageSmoothingEnabled = true
  ctx.imageSmoothingQuality = 'high'
  ctx.fillStyle = '#fff' // transparent PNGs become white, not black
  ctx.fillRect(0, 0, size.width, size.height)
  ctx.drawImage(source, 0, 0, size.width, size.height)
  return new Promise<Blob>((resolve, reject) =>
    canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not compress the photo'))), 'image/jpeg', quality),
  )
}

export async function preparePhoto(file: File): Promise<PreparedPhoto> {
  const bytes = await file.arrayBuffer()
  const checksumSha256 = await sha256Hex(bytes)
  const capturedAt = file.type === 'image/jpeg' ? exifCapturedAt(bytes.slice(0, 256 * 1024)) : null
  // Browsers apply the EXIF rotation when decoding, so portrait photos stay portrait.
  const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' })
  try {
    const { width, height } = bitmap
    // The gallery version first, then the thumbnail from it: faster than scaling the full image twice.
    const web = await encode(bitmap, width, height, WEB_LONG_EDGE, WEB_QUALITY)
    const webBitmap = await createImageBitmap(web)
    try {
      const thumbnail = await encode(webBitmap, webBitmap.width, webBitmap.height, THUMB_LONG_EDGE, THUMB_QUALITY)
      return { web, thumbnail, width, height, capturedAt, checksumSha256 }
    } finally {
      webBitmap.close()
    }
  } finally {
    bitmap.close()
  }
}
