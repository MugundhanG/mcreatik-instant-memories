import { describe, expect, it } from 'vitest'
import { jpegPagesToPdf } from './pdf'

/** Reads a Blob as a latin-1 string so byte offsets equal string indexes. */
async function text(blob: Blob) {
  const bytes = new Uint8Array(await blob.arrayBuffer())
  let s = ''
  for (const b of bytes) s += String.fromCharCode(b)
  return s
}

const jpeg = (n: number) => ({ bytes: Uint8Array.from([0xff, 0xd8, ...Array(n).fill(7), 0xff, 0xd9]), width: 1240, height: 1754 })

describe('jpegPagesToPdf', () => {
  it('writes a valid header, trailer and page count', async () => {
    const pdf = await text(jpegPagesToPdf([jpeg(10), jpeg(20), jpeg(30)]))
    expect(pdf.startsWith('%PDF-1.4')).toBe(true)
    expect(pdf.trimEnd().endsWith('%%EOF')).toBe(true)
    expect(pdf).toContain('/Count 3')
    expect(pdf.match(/\/Type \/Page /g)).toHaveLength(3)
    expect(pdf.match(/\/DCTDecode/g)).toHaveLength(3)
    expect(pdf).toContain('/MediaBox [0 0 595.28 841.89]')
  })

  it('has xref offsets that point at each object', async () => {
    const blob = jpegPagesToPdf([jpeg(100), jpeg(5)])
    const pdf = await text(blob)
    const start = Number(/startxref\n(\d+)\n/.exec(pdf)![1])
    expect(pdf.slice(start, start + 4)).toBe('xref')
    const lines = pdf.slice(start).split('\n')
    const [, size] = lines[1].split(' ').map(Number)
    expect(size).toBe(1 + 2 + 2 * 3)
    for (let n = 1; n < size; n++) {
      const offset = Number(lines[2 + n].slice(0, 10))
      expect(pdf.slice(offset, offset + `${n} 0 obj`.length)).toBe(`${n} 0 obj`)
    }
  })

  it('stores the JPEG bytes untouched and declares the right stream length', async () => {
    const pdf = await text(jpegPagesToPdf([jpeg(40)]))
    const len = Number(/\/Length (\d+) >>\nstream\n\xff\xd8/.exec(pdf)![1])
    expect(len).toBe(2 + 40 + 2)
  })

  it('refuses an empty document', () => {
    expect(() => jpegPagesToPdf([])).toThrow()
  })
})
