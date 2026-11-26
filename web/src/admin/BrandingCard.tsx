import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react'
import { ApiError } from '../lib/api'
import type { Brand, MarkSettings } from '../lib/types'
import { DEFAULT_MARK } from '../lib/watermark'
import { WatermarkPreview } from './WatermarkPreview'
import { Button, Card, ErrorText, Field, inputClass } from './ui'
import { formatPhone } from '../shared/phone'
import { THEMES, type BoardTheme } from '../shared/printDesign'
import { phoneError } from '../shared/validate'

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
  update: (input: { displayName?: string; instagram?: string; phone?: string; logoScale?: number; mark?: MarkSettings }) => Promise<Brand>
  /** The studio's logo file through the API, for the watermark preview (a canvas may not read the public media URL). */
  loadLogo?: () => Promise<Blob>
  upload: (png: Blob) => Promise<Brand>
  remove: () => Promise<Brand>
}

/** The poster preview's paper, following the studio's board design (Account → Studio board). */
const PREVIEW_PAPER: Record<BoardTheme, string> = {
  cream: 'radial-gradient(ellipse at 50% 35%, #fffdf8, #f3ead8)',
  dark: 'radial-gradient(ellipse at 50% 30%, #2b2521, #1a1714)',
  white: '#ffffff',
}

/** "@name" or an instagram.com link → the handle, as the server saves it. */
const handleOf = (raw: string) => raw.trim().replace(/^(?:https?:\/\/)?(?:www\.)?instagram\.com\//i, '').replace(/[/?#].*$/, '').replace(/^@/, '')

/** The number as the server would save it (enough for the preview). */
function previewPhone(raw: string): string | null {
  const digits = raw.replace(/[\s().-]/g, '')
  if (!/^\+?\d{8,15}$/.test(digits) && !/^0\d{10}$/.test(digits)) return null
  if (/^0?[6-9]\d{9}$/.test(digits)) return `+91${digits.slice(-10)}`
  if (/^91[6-9]\d{9}$/.test(digits)) return `+${digits}`
  return digits
}

const MIN_SCALE = 50
const MAX_SCALE = 200

/**
 * A studio's name, logo, Instagram and poster logo size: what guests see on its galleries and QR posters.
 * {@code onSaved} receives the brand as saved (its name is the studio name).
 */
export function BrandingCard({ brand, actions, onSaved, title = 'Studio branding', embedded = false, theme = 'cream', onCancel }: {
  brand: Brand
  actions: BrandingActions
  onSaved: (b: Brand) => void
  title?: string
  /** Inside another card (Studios page): no card frame of its own. */
  embedded?: boolean
  /** The studio's board design: the preview below uses its colours. */
  theme?: BoardTheme
  /** Called after Cancel put the form back to the saved values (e.g. to close the editor). */
  onCancel?: () => void
}) {
  const [name, setName] = useState(brand.name)
  const [instagram, setInstagram] = useState(brand.instagram ?? '')
  const [phone, setPhone] = useState(brand.phone ? formatPhone(brand.phone) : '')
  const [scale, setScale] = useState(brand.logoScale ?? 100)
  const [mark, setMark] = useState<MarkSettings>(brand.mark ?? DEFAULT_MARK)
  const [busy, setBusy] = useState<'save' | 'logo' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [phoneTouched, setPhoneTouched] = useState(false)
  const phoneMsg = phoneTouched ? phoneError(phone) : null
  const fileInput = useRef<HTMLInputElement>(null)
  const logoUrl = brand.logoUrl
  const handle = handleOf(instagram)
  const markPhone = previewPhone(phone)

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
        setPhone(b.phone ? formatPhone(b.phone) : '')
        setMark(b.mark ?? DEFAULT_MARK)
        setSaved(true)
      }
    } catch (err) {
      setError(message(err, fallback))
    } finally {
      setBusy(null)
    }
  }

  const cancel = () => {
    setName(brand.name)
    setInstagram(brand.instagram ?? '')
    setPhone(brand.phone ? formatPhone(brand.phone) : '')
    setScale(brand.logoScale ?? 100)
    setMark(brand.mark ?? DEFAULT_MARK)
    setPhoneTouched(false)
    setError(null)
    onCancel?.()
  }

  const save = (e: FormEvent) => {
    e.preventDefault()
    setPhoneTouched(true)
    if (phoneError(phone)) return
    void run('save', () => actions.update({ displayName: name.trim(), instagram: instagram.trim(), phone: phone.trim(), logoScale: scale, mark }), 'Could not save')
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
      <form onSubmit={save} noValidate className="space-y-4">
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
              <div className="flex flex-col items-center px-4 py-4" style={{ minHeight: 120, background: PREVIEW_PAPER[theme] }}>
                <img src={logoUrl} alt="" className="w-auto object-contain" style={{ height: 42 * scale / 100, maxWidth: 160 * scale / 100 }} />
                <p className="mt-2 font-display text-base font-semibold tracking-[0.22em] uppercase" style={{ color: THEMES[theme].accent }}>{name || brand.name}</p>
                <p className="mt-2 rounded-full border px-2 py-0.5 text-[7px] font-semibold tracking-[0.3em]" style={{ borderColor: THEMES[theme].frame, color: THEMES[theme].accent }}>MCREATIK INSTANT MEMORIES</p>
              </div>
            </div>
          </div>
        )}
        <Field label="Studio name" hint="Shown to guests on your galleries, QR posters and printed cards.">
          <input className={inputClass} required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        </Field>
        <Field label="Instagram" hint="Guests get a Follow button. Leave empty to hide it.">
          <input className={inputClass} value={instagram} onChange={(e) => setInstagram(e.target.value)} placeholder="@yourstudio" />
        </Field>
        <Field label="Contact number (WhatsApp)" hint="Guests can WhatsApp or call you from your gallery, and it is printed on your studio board and QR poster. Leave empty to hide it.">
          <input className={inputClass} type="tel" inputMode="tel" autoComplete="tel" aria-invalid={!!phoneMsg} value={phone} onChange={(e) => setPhone(e.target.value)} onBlur={() => setPhoneTouched(true)} placeholder="98765 43210" />
        </Field>
        <ErrorText>{phoneMsg}</ErrorText>
        <fieldset className="space-y-3 rounded-xl border border-line p-4" data-testid="mark-settings">
          <legend className="px-1 text-sm font-medium">Watermark on guest downloads</legend>
          <p className="text-xs text-muted">Guests’ downloads and shares carry this. Turn it off for an event in its Settings.</p>
          <div role="radiogroup" aria-label="Watermark style" className="grid gap-2 sm:grid-cols-2">
            <ChoiceRadio name="mark-style" checked={mark.style === 'CORNER'} onChange={() => setMark({ ...mark, style: 'CORNER' })}
              label="Corner mark" hint="A small white mark on the photo." />
            <ChoiceRadio name="mark-style" checked={mark.style === 'STRIP'} onChange={() => setMark({ ...mark, style: 'STRIP' })}
              label="Signature strip" hint="A band under the photo, so it never covers it." />
          </div>
          <div className="space-y-1.5">
            <p className="text-xs font-medium text-muted">Show</p>
            <DetailCheck label="Logo" checked={mark.logo && !!logoUrl} available={!!logoUrl} missing="Upload your logo above"
              onChange={(v) => setMark({ ...mark, logo: v })} />
            <DetailCheck label="Studio name" checked={mark.name} available onChange={(v) => setMark({ ...mark, name: v })} />
            <DetailCheck label="Instagram handle" checked={mark.instagram && !!handle} available={!!handle} missing="Add your Instagram above"
              onChange={(v) => setMark({ ...mark, instagram: v })} />
            <DetailCheck label="Phone number" checked={mark.phone && !!markPhone} available={!!markPhone} missing="Add your contact number above"
              onChange={(v) => setMark({ ...mark, phone: v })} />
            <p className="text-xs text-muted">If none of these is available, your studio name is used.</p>
          </div>
          <div className="flex flex-wrap gap-x-6 gap-y-3">
            <Segmented label="Position" value={mark.position} options={[['LEFT', 'Left'], ['CENTER', 'Centre'], ['RIGHT', 'Right']]}
              onChange={(position) => setMark({ ...mark, position })} />
            {mark.style === 'CORNER' && (
              <Segmented label="Size" value={mark.size} options={[['SMALL', 'Small'], ['MEDIUM', 'Medium']]}
                onChange={(size) => setMark({ ...mark, size })} />
            )}
          </div>
          <WatermarkPreview settings={mark} name={name.trim() || brand.name} instagram={handle || null} phone={markPhone}
            logoUrl={logoUrl} logoScale={scale} loadLogo={actions.loadLogo} />
        </fieldset>
        <ErrorText>{error}</ErrorText>
        <div className="flex items-center justify-end gap-3">
          {saved && <span role="status" className="text-sm text-emerald-700">Saved ✓</span>}
          <Button onClick={cancel} disabled={busy !== null}>Cancel</Button>
          <Button type="submit" variant="primary" disabled={busy !== null}>{busy === 'save' ? 'Saving…' : 'Save'}</Button>
        </div>
      </form>
    </>
  )
  return embedded ? <div data-testid="branding-editor">{content}</div> : <Card title={title}>{content}</Card>
}

function ChoiceRadio({ name, checked, onChange, label, hint }: { name: string; checked: boolean; onChange: () => void; label: string; hint: string }) {
  return (
    <label className={`flex cursor-pointer items-start gap-2 rounded-lg border p-3 text-sm ${checked ? 'border-ink bg-stone-50' : 'border-line'}`}>
      <input type="radio" name={name} checked={checked} onChange={onChange} className="mt-0.5 accent-[#b37a2c]" />
      <span><span className="font-medium">{label}</span><span className="block text-xs text-muted">{hint}</span></span>
    </label>
  )
}

function DetailCheck({ label, checked, available, missing, onChange }: { label: string; checked: boolean; available: boolean; missing?: string; onChange: (v: boolean) => void }) {
  const hintId = `mark-${label.replace(/\W+/g, '-').toLowerCase()}-hint`
  return (
    <div>
      <label className={`flex items-center gap-2 text-sm ${available ? '' : 'text-muted'}`}>
        <input type="checkbox" checked={checked} disabled={!available} aria-describedby={available ? undefined : hintId}
          onChange={(e) => onChange(e.target.checked)} className="accent-[#b37a2c]" />
        {label}
      </label>
      {!available && missing && <p id={hintId} className="ml-6 text-xs text-muted">{missing}</p>}
    </div>
  )
}

function Segmented<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: [T, string][]; onChange: (v: T) => void }) {
  return (
    <div role="radiogroup" aria-label={label}>
      <p className="mb-1 text-xs font-medium text-muted">{label}</p>
      <div className="inline-flex overflow-hidden rounded-lg border border-line">
        {options.map(([v, text]) => (
          <label key={v} className={`cursor-pointer px-3 py-1.5 text-sm ${value === v ? 'bg-ink text-white' : 'bg-white text-ink'} has-[:focus-visible]:outline-2`}>
            <input type="radio" name={`mark-${label.toLowerCase()}`} value={v} checked={value === v} onChange={() => onChange(v)} className="sr-only" />
            {text}
          </label>
        ))}
      </div>
    </div>
  )
}
