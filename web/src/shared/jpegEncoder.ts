/**
 * A small baseline JPEG encoder that takes the picture a strip of rows at a time.
 *
 * Print files are huge (the studio board is 5475 × 7275 px). Phones cannot hold a canvas that large, so the browser's
 * own canvas.toBlob gives a blank, shrunk or failed file there. Encoding strip by strip needs only a small canvas at
 * a time and gives the same full-size file on every device. Full colour (4:4:4, no chroma subsampling) keeps QR
 * codes and small text crisp; the JFIF header carries the print density (300 pixels per inch).
 */

const ZIGZAG = [
  0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
  35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
]

const Q_LUMA = [
  16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
  18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
]
const Q_CHROMA = [
  17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
  99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
]

// The standard Huffman tables (JPEG Annex K): code counts per length, then the symbols.
const DC_LUMA_BITS = [0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0]
const DC_CHROMA_BITS = [0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0]
const DC_VALUES = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]
const AC_LUMA_BITS = [0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d]
const AC_LUMA_VALUES = [
  0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
  0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
  0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
  0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
  0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6,
  0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
  0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa,
]
const AC_CHROMA_BITS = [0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77]
const AC_CHROMA_VALUES = [
  0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
  0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
  0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58,
  0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
  0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4,
  0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
  0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa,
]

interface Huffman { code: Uint16Array; size: Uint8Array }

function huffman(bits: number[], values: number[]): Huffman {
  const code = new Uint16Array(256)
  const size = new Uint8Array(256)
  let next = 0
  let k = 0
  for (let length = 1; length <= 16; length++) {
    for (let i = 0; i < bits[length - 1]; i++) {
      code[values[k]] = next++
      size[values[k]] = length
      k++
    }
    next <<= 1
  }
  return { code, size }
}

function quantTable(base: number[], quality: number): Uint8Array {
  const scale = quality < 50 ? 5000 / quality : 200 - quality * 2
  return Uint8Array.from(base, (v) => Math.min(255, Math.max(1, Math.floor((v * scale + 50) / 100))))
}

/** A growing byte buffer with the JPEG bit writer (0xFF bytes are followed by a zero, as the format requires). */
class Output {
  private chunks: Uint8Array[] = []
  private buffer = new Uint8Array(1 << 20)
  private used = 0
  private acc = 0
  private accBits = 0

  byte(b: number) {
    if (this.used === this.buffer.length) {
      this.chunks.push(this.buffer)
      this.buffer = new Uint8Array(1 << 20)
      this.used = 0
    }
    this.buffer[this.used++] = b
  }

  word(w: number) {
    this.byte((w >> 8) & 0xff)
    this.byte(w & 0xff)
  }

  bytes(list: ArrayLike<number>) {
    for (let i = 0; i < list.length; i++) this.byte(list[i])
  }

  bits(code: number, length: number) {
    this.acc = (this.acc << length) | code
    this.accBits += length
    while (this.accBits >= 8) {
      const b = (this.acc >>> (this.accBits - 8)) & 0xff
      this.byte(b)
      if (b === 0xff) this.byte(0)
      this.accBits -= 8
    }
    this.acc &= (1 << this.accBits) - 1
  }

  flushBits() {
    if (this.accBits > 0) this.bits((1 << (8 - this.accBits)) - 1, 8 - this.accBits)
  }

  parts(): BlobPart[] {
    return [...this.chunks, this.buffer.subarray(0, this.used)] as BlobPart[]
  }
}

// Scaled cosines for the forward DCT (plain separable form: clear and exact enough at high quality).
const COS = (() => {
  const t = new Float32Array(64)
  for (let u = 0; u < 8; u++) {
    const c = u === 0 ? Math.SQRT1_2 : 1
    for (let x = 0; x < 8; x++) t[u * 8 + x] = (c / 2) * Math.cos(((2 * x + 1) * u * Math.PI) / 16)
  }
  return t
})()

export interface StripEncoder {
  /** The next rows, top to bottom, as RGBA (canvas image data). Every strip but the last must be a multiple of 8 rows. */
  addStrip(rgba: Uint8ClampedArray, rows: number): void
  finish(): Blob
}

/** Starts a JPEG of {@code width} × {@code height} pixels; feed it the rows with addStrip, then finish. */
export function jpegStripEncoder(width: number, height: number, opts: { quality?: number; dpi?: number } = {}): StripEncoder {
  const quality = opts.quality ?? 95
  const dpi = opts.dpi ?? 300
  const qLuma = quantTable(Q_LUMA, quality)
  const qChroma = quantTable(Q_CHROMA, quality)
  const dcLuma = huffman(DC_LUMA_BITS, DC_VALUES)
  const dcChroma = huffman(DC_CHROMA_BITS, DC_VALUES)
  const acLuma = huffman(AC_LUMA_BITS, AC_LUMA_VALUES)
  const acChroma = huffman(AC_CHROMA_BITS, AC_CHROMA_VALUES)
  const out = new Output()

  // Headers: SOI, JFIF (with the print density), quantisation tables, frame, Huffman tables, scan.
  out.word(0xffd8)
  out.word(0xffe0); out.word(16); out.bytes([0x4a, 0x46, 0x49, 0x46, 0, 1, 1, 1]); out.word(dpi); out.word(dpi); out.bytes([0, 0])
  for (const [id, table] of [[0, qLuma], [1, qChroma]] as const) {
    out.word(0xffdb); out.word(67); out.byte(id)
    for (let i = 0; i < 64; i++) out.byte(table[ZIGZAG[i]])
  }
  out.word(0xffc0); out.word(17); out.byte(8); out.word(height); out.word(width); out.byte(3)
  out.bytes([1, 0x11, 0, 2, 0x11, 1, 3, 0x11, 1])
  for (const [id, bits, values] of [[0x00, DC_LUMA_BITS, DC_VALUES], [0x10, AC_LUMA_BITS, AC_LUMA_VALUES],
    [0x01, DC_CHROMA_BITS, DC_VALUES], [0x11, AC_CHROMA_BITS, AC_CHROMA_VALUES]] as const) {
    out.word(0xffc4); out.word(19 + values.length); out.byte(id); out.bytes(bits); out.bytes(values)
  }
  out.word(0xffda); out.word(12); out.byte(3); out.bytes([1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0])

  const block = new Float32Array(64)
  const temp = new Float32Array(64)
  const coeff = new Int16Array(64)
  const yBlock = new Float32Array(64)
  const cbBlock = new Float32Array(64)
  const crBlock = new Float32Array(64)
  const lastDc = [0, 0, 0]
  let rowsDone = 0

  const encodeBlock = (source: Float32Array, q: Uint8Array, dc: Huffman, ac: Huffman, component: number) => {
    // Forward DCT: rows, then columns.
    for (let y = 0; y < 8; y++) {
      for (let u = 0; u < 8; u++) {
        let sum = 0
        for (let x = 0; x < 8; x++) sum += source[y * 8 + x] * COS[u * 8 + x]
        temp[y * 8 + u] = sum
      }
    }
    for (let u = 0; u < 8; u++) {
      for (let v = 0; v < 8; v++) {
        let sum = 0
        for (let y = 0; y < 8; y++) sum += temp[y * 8 + u] * COS[v * 8 + y]
        block[v * 8 + u] = sum
      }
    }
    for (let i = 0; i < 64; i++) coeff[i] = Math.round(block[ZIGZAG[i]] / q[ZIGZAG[i]])

    const diff = coeff[0] - lastDc[component]
    lastDc[component] = coeff[0]
    let magnitude = diff < 0 ? -diff : diff
    let category = 0
    while (magnitude) { category++; magnitude >>= 1 }
    out.bits(dc.code[category], dc.size[category])
    if (category) out.bits(diff < 0 ? diff + (1 << category) - 1 : diff, category)

    let last = 63
    while (last > 0 && coeff[last] === 0) last--
    let run = 0
    for (let i = 1; i <= last; i++) {
      const value = coeff[i]
      if (value === 0) { run++; continue }
      while (run > 15) { out.bits(ac.code[0xf0], ac.size[0xf0]); run -= 16 }
      let m = value < 0 ? -value : value
      let cat = 0
      while (m) { cat++; m >>= 1 }
      const symbol = (run << 4) | cat
      out.bits(ac.code[symbol], ac.size[symbol])
      out.bits(value < 0 ? value + (1 << cat) - 1 : value, cat)
      run = 0
    }
    if (last < 63) out.bits(ac.code[0], ac.size[0])
  }

  return {
    addStrip(rgba, rows) {
      if (rowsDone + rows > height) throw new Error('Too many rows for this JPEG')
      const lastStrip = rowsDone + rows === height
      if (!lastStrip && rows % 8 !== 0) throw new Error('A strip must be a multiple of 8 rows')
      for (let by = 0; by < rows; by += 8) {
        for (let bx = 0; bx < width; bx += 8) {
          for (let y = 0; y < 8; y++) {
            const sy = Math.min(by + y, rows - 1) // repeat the last row / column to fill the edge blocks
            for (let x = 0; x < 8; x++) {
              const p = (sy * width + Math.min(bx + x, width - 1)) * 4
              const r = rgba[p]
              const g = rgba[p + 1]
              const b = rgba[p + 2]
              const i = y * 8 + x
              yBlock[i] = 0.299 * r + 0.587 * g + 0.114 * b - 128
              cbBlock[i] = -0.168736 * r - 0.331264 * g + 0.5 * b
              crBlock[i] = 0.5 * r - 0.418688 * g - 0.081312 * b
            }
          }
          encodeBlock(yBlock, qLuma, dcLuma, acLuma, 0)
          encodeBlock(cbBlock, qChroma, dcChroma, acChroma, 1)
          encodeBlock(crBlock, qChroma, dcChroma, acChroma, 2)
        }
      }
      rowsDone += rows
    },
    finish() {
      if (rowsDone !== height) throw new Error(`The JPEG got ${rowsDone} of ${height} rows`)
      out.flushBits()
      out.word(0xffd9)
      return new Blob(out.parts(), { type: 'image/jpeg' })
    },
  }
}
