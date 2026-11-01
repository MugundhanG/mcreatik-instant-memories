import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { ApiError } from '../lib/api'
import type { Brand } from '../lib/types'
import { Button, Card, ErrorText, Field, inputClass } from './ui'

const LOGO_MAX_EDGE = 800

/** Any image the browser can show (PNG, JPEG, WebP, SVG) → a PNG of at most 800 px, transparency kept. */
async function logoPng(file: File): Promise<Blob> {
  const url = URL.createObjectURL(file)
  try {
    const img = new Image()
    img.src = url
    await img.decode()
    const w = img.naturalWidth || 800
    const h = img.naturalHeight || 300
    const scale = Math.min(1, LOGO_MAX_EDGE / Math.max(w, h))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(w * scale))
    canvas.height = Math.max(1, Math.round(h * scale))
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('This browser cannot read the logo')
    ctx.imageSmoothingQuality = 'high'
    ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
    return await new Promise((resolve, reject) =>
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('Could not read the logo'))), 'image/png'),
    )
  } finally {
    URL.revokeObjectURL(url)
  }
}

const message = (err: unknown, fallback: string) => (err instanceof ApiError || err instanceof Error ? err.message : fallback)

/** Saving branding: the studio's own account, or McreatiK editing a studio (Studios page). */
export interface BrandingActions {
  update: (input: { displayName?: string; instagram?: string; logoScale?: number }) => Promise<Brand>
  upload: (png: Blob) => Promise<Brand>
  remove: () => Promise<Brand>
}

const MIN_SCALE = 50
const MAX_SCALE = 200

/**
 * A studio's name, logo, Instagram and poster logo size: what guests see on its galleries and QR posters.
 * {@code onSaved} receives the brand as saved (its name is the studio name).
 */
export function BrandingCard({ brand, actions, onSaved, title = 'Studio branding', embedded = false }: {
  brand: Brand
  actions: BrandingActions
  onSaved: (b: Brand) => void
  title?: string
  /** Inside another card (Studios page): no card frame of its own. */
  embedded?: boolean
}) {
  const [name, setName] = useState(brand.name)
  const [instagram, setInstagram] = useState(brand.instagram ?? '')
  const [scale, setScale] = useState(brand.logoScale ?? 100)
  const [busy, setBusy] = useState<'save' | 'logo' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const logoUrl = brand.logoUrl

  useEffect(() => {
    if (!saved) return
    const t = setTimeout(() => setSaved(false), 2500)
    return () => clearTimeout(t)
  }, [saved])

  const run = async (kind: 'save' | 'logo', action: () => Promise<Brand>, fallback: string) => {
    setBusy(kind)
    setError(null)
    try {
      const b = await action()
      onSaved(b)
      if (kind === 'save') {
        setInstagram(b.instagram ?? '')
        setSaved(true)
      }
    } catch (err) {
      setError(message(err, fallback))
    } finally {
      setBusy(null)
    }
  }

  const save = (e: FormEvent) => {
    e.preventDefault()
    void run('save', () => actions.update({ displayName: name.trim(), instagram: instagram.trim(), logoScale: scale }), 'Could not save')
  }

  const pickLogo = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (file) await run('logo', async () => actions.upload(await logoPng(file)), 'Could not upload the logo')
  }

  const content = (
    <>
      <p className="mb-4 text-sm text-muted">Guests see this on the galleries and QR posters.</p>
      <div className="mb-5 flex items-center gap-4">
        <div className="flex h-20 w-36 shrink-0 items-center justify-center rounded-xl border border-line bg-stone-50 p-2">
          {logoUrl
            ? <img src={logoUrl} alt="Your logo" data-testid="logo-preview" className="max-h-full max-w-full object-contain" />
            : <span className="text-xs text-muted">No logo</span>}
        </div>
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => fileInput.current?.click()} disabled={busy !== null}>
            {busy === 'logo' ? 'Uploading…' : logoUrl ? 'Change logo' : 'Upload logo'}
          </Button>
          {logoUrl && <Button variant="ghost" onClick={() => run('logo', actions.remove, 'Could not remove the logo')} disabled={busy !== null}>Remove</Button>}
        </div>
        <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" className="hidden" onChange={pickLogo} aria-label="Logo file" />
      </div>
      <p className="-mt-3 mb-5 text-xs text-muted">Best: a PNG with a transparent background.</p>
      <form onSubmit={save} className="space-y-4">
        {logoUrl && (
          <div>
            <label className="flex items-center justify-between text-sm font-medium" htmlFor="logo-scale">
              <span>Logo size on the QR poster</span>
              <span className="tabular-nums text-muted">{scale}%</span>
            </label>
            <input id="logo-scale" type="range" min={MIN_SCALE} max={MAX_SCALE} step={5} value={scale}
              onChange={(e) => setScale(Number(e.target.value))} className="mt-2 w-full accent-[#b37a2c]" />
            {/* Live preview of the poster's top, at its real proportions (the A4 poster is 210 mm wide). */}
            <div className="mt-3 overflow-hidden rounded-lg border border-line" data-testid="poster-preview">
              <div className="flex flex-col items-center bg-[radial-gradient(ellipse_at_50%_35%,#fffdf8,#f3ead8)] px-4 py-4" style={{ minHeight: 120 }}>
                <img src={logoUrl} alt="" className="w-auto object-contain" style={{ height: 42 * scale / 100, maxWidth: 160 * scale / 100 }} />
                <p className="mt-2 font-display text-base font-semibold tracking-[0.22em] text-[#7a4f17] uppercase">{name || brand.name}</p>
                <p className="mt-2 rounded-full border border-[#d9a94e] px-2 py-0.5 text-[7px] font-semibold tracking-[0.3em] text-[#8a5a1c]">LIVE PHOTO GALLERY</p>
              </div>
            </div>
          </div>
        )}
        <Field label="Studio name">
          <input className={inputClass} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Instagram" hint="Guests get a Follow button. Leave empty to hide it.">
          <input className={inputClass} value={instagram} onChange={(e) => setInstagram(e.target.value)} placeholder="@yourstudio" />
        </Field>
        <ErrorText>{error}</ErrorText>
        <div className="flex items-center justify-end gap-3">
          {saved && <span role="status" className="text-sm text-emerald-700">Saved ✓</span>}
          <Button type="submit" variant="primary" disabled={busy !== null}>{busy === 'save' ? 'Saving…' : 'Save'}</Button>
        </div>
      </form>
    </>
  )
  return embedded ? <div data-testid="branding-editor">{content}</div> : <Card title={title}>{content}</Card>
}
