import QRCode from 'qrcode'

/**
 * QR codes only ever contain the public gallery URL (e.g. https://gallery.mcreatik.com/e/arun-priya-7k3d):
 * no tokens, ids or anything else sensitive.
 */
export function qrSvg(url: string): Promise<string> {
  return QRCode.toString(url, { type: 'svg', errorCorrectionLevel: 'M', margin: 2, color: { dark: '#1c1917', light: '#ffffff' } })
}

/** High-resolution PNG (print-ready) rendered from the SVG in the browser. */
export async function qrPngBlob(url: string, size = 2048): Promise<Blob> {
  const svg = await qrSvg(url)
  const img = new Image()
  const svgUrl = URL.createObjectURL(new Blob([svg], { type: 'image/svg+xml' }))
  try {
    await new Promise<void>((resolve, reject) => {
      img.onload = () => resolve()
      img.onerror = () => reject(new Error('Could not render QR'))
      img.src = svgUrl
    })
    const canvas = document.createElement('canvas')
    canvas.width = size
    canvas.height = size
    const ctx = canvas.getContext('2d')!
    ctx.imageSmoothingEnabled = false
    ctx.drawImage(img, 0, 0, size, size)
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('PNG export failed'))), 'image/png'),
    )
  } finally {
    URL.revokeObjectURL(svgUrl)
  }
}
