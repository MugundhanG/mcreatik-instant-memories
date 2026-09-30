import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { qrPngBlob, qrSvg } from '../shared/qr'
import { saveBlob } from '../shared/saveBlob'
import { Button, Card } from './ui'

export function QrCode({ url, className = '' }: { url: string; className?: string }) {
  const [svg, setSvg] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    qrSvg(url).then((s) => active && setSvg(s))
    return () => {
      active = false
    }
  }, [url])
  return (
    <div
      role="img"
      aria-label={`QR code for ${url}`}
      data-qr-url={url}
      className={`aspect-square [&>svg]:size-full ${className}`}
      // SVG generated locally by the qrcode library from our own URL.
      dangerouslySetInnerHTML={svg ? { __html: svg } : undefined}
    />
  )
}

export function QrPanel({ eventId, eventSlug, galleryUrl }: { eventId: string; eventSlug: string; galleryUrl: string }) {
  const [copied, setCopied] = useState(false)

  const copy = async () => {
    await navigator.clipboard?.writeText(galleryUrl)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Card title="Guest QR code">
      <div className="mx-auto max-w-56 rounded-lg border border-line p-2">
        <QrCode url={galleryUrl} />
      </div>
      <p className="mt-3 text-center text-xs break-all text-muted">{galleryUrl}</p>
      <div className="mt-4 grid grid-cols-2 gap-2">
        <Button onClick={async () => saveBlob(await qrPngBlob(galleryUrl), `${eventSlug}-qr.png`)}>Download PNG</Button>
        <Button onClick={async () => saveBlob(new Blob([await qrSvg(galleryUrl)], { type: 'image/svg+xml' }), `${eventSlug}-qr.svg`)}>
          Download SVG
        </Button>
        <Link
          to={`/admin/events/${eventId}/print`}
          target="_blank"
          className="inline-flex h-9 items-center justify-center rounded-lg border border-line bg-white px-3.5 text-sm font-medium hover:bg-stone-50"
        >
          Print poster
        </Link>
        <Button onClick={copy}>{copied ? 'Copied' : 'Copy link'}</Button>
      </div>
    </Card>
  )
}
