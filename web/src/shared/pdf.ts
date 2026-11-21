/**
 * A tiny PDF writer for one job: a document whose pages are full-page JPEG pictures (the guest report). No fonts
 * or text objects are needed, so the file is just a catalog, a page tree and, per page, an image, a content stream
 * and a page object. JPEG bytes go in untouched (`/DCTDecode`), so nothing is re-encoded.
 */

/** A4 in PDF points (1/72 inch). */
export const A4_PT = { width: 595.28, height: 841.89 }

export interface PdfPage {
  /** A baseline or progressive JPEG file. */
  bytes: Uint8Array
  /** The picture's size in pixels. */
  width: number
  height: number
}

const ascii = (text: string) => Uint8Array.from(text, (c) => c.charCodeAt(0) & 0xff)

/** Builds a multi-page A4 PDF; each page shows its JPEG stretched over the whole sheet. */
export function jpegPagesToPdf(pages: PdfPage[]): Blob {
  if (pages.length === 0) throw new Error('A PDF needs at least one page')
  const chunks: Uint8Array[] = []
  const offsets: number[] = [] // byte offset of object n (1-based) at index n
  let length = 0
  const push = (chunk: Uint8Array | string) => {
    const bytes = typeof chunk === 'string' ? ascii(chunk) : chunk
    chunks.push(bytes)
    length += bytes.length
  }
  const object = (n: number, body: (Uint8Array | string)[]) => {
    offsets[n] = length
    push(`${n} 0 obj\n`)
    body.forEach(push)
    push('\nendobj\n')
  }

  // Header plus a comment of high-bit bytes, which tells tools the file is binary.
  push('%PDF-1.4\n')
  push(Uint8Array.of(0x25, 0xe2, 0xe3, 0xcf, 0xd3, 0x0a))

  // Objects: 1 catalog, 2 page tree, then three per page (page, image, content).
  const kids = pages.map((_, i) => `${3 + i * 3} 0 R`).join(' ')
  object(1, ['<< /Type /Catalog /Pages 2 0 R >>'])
  object(2, [`<< /Type /Pages /Kids [${kids}] /Count ${pages.length} >>`])
  pages.forEach((page, i) => {
    const pageId = 3 + i * 3
    const imageId = pageId + 1
    const contentId = pageId + 2
    const draw = `q ${A4_PT.width} 0 0 ${A4_PT.height} 0 0 cm /Im0 Do Q`
    object(pageId, [`<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${A4_PT.width} ${A4_PT.height}] /Resources << /XObject << /Im0 ${imageId} 0 R >> >> /Contents ${contentId} 0 R >>`])
    object(imageId, [
      `<< /Type /XObject /Subtype /Image /Width ${page.width} /Height ${page.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${page.bytes.length} >>\nstream\n`,
      page.bytes,
      '\nendstream',
    ])
    object(contentId, [`<< /Length ${draw.length} >>\nstream\n${draw}\nendstream`])
  })

  // Cross-reference table: one 20-byte line per object, plus the free entry 0.
  const count = 3 + pages.length * 3
  const xref = length
  let table = `xref\n0 ${count}\n0000000000 65535 f \n`
  for (let n = 1; n < count; n++) table += `${String(offsets[n]).padStart(10, '0')} 00000 n \n`
  push(table)
  push(`trailer\n<< /Size ${count} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`)
  return new Blob(chunks as BlobPart[], { type: 'application/pdf' })
}
