import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { adminApi, ApiError } from '../lib/api'
import type { Account } from '../lib/types'
import { auth } from './auth'
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

/** A studio's name, logo and Instagram: what guests see on its galleries and posters. */
export function BrandingCard({ account, onChange }: { account: Account; onChange: (a: Account) => void }) {
  const [name, setName] = useState(account.displayName)
  const [instagram, setInstagram] = useState(account.brand?.instagram ?? '')
  const [busy, setBusy] = useState<'save' | 'logo' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const logoUrl = account.brand?.logoUrl ?? null

  useEffect(() => {
    if (!saved) return
    const t = setTimeout(() => setSaved(false), 2500)
    return () => clearTimeout(t)
  }, [saved])

  const applied = (a: Account) => {
    onChange(a)
    auth.update({ displayName: a.displayName })
  }

  const save = async (e: FormEvent) => {
    e.preventDefault()
    setBusy('save')
    setError(null)
    try {
      const a = await adminApi.updateAccount({ displayName: name.trim(), instagram: instagram.trim() })
      setInstagram(a.brand?.instagram ?? '')
      applied(a)
      setSaved(true)
    } catch (err) {
      setError(message(err, 'Could not save'))
    } finally {
      setBusy(null)
    }
  }

  const pickLogo = async (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    setBusy('logo')
    setError(null)
    try {
      applied(await adminApi.uploadLogo(await logoPng(file)))
    } catch (err) {
      setError(message(err, 'Could not upload the logo'))
    } finally {
      setBusy(null)
    }
  }

  const removeLogo = async () => {
    setBusy('logo')
    setError(null)
    try {
      applied(await adminApi.removeLogo())
    } catch (err) {
      setError(message(err, 'Could not remove the logo'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card title="Studio branding">
      <p className="mb-4 text-sm text-muted">Guests see this on your galleries and QR posters.</p>
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
          {logoUrl && <Button variant="ghost" onClick={removeLogo} disabled={busy !== null}>Remove</Button>}
        </div>
        <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/webp,image/svg+xml" className="hidden" onChange={pickLogo} aria-label="Logo file" />
      </div>
      <p className="-mt-3 mb-5 text-xs text-muted">Best: a PNG with a transparent background.</p>
      <form onSubmit={save} className="space-y-4">
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
    </Card>
  )
}
