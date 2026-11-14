import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { prepareSelfie } from '../admin/prepareUpload'
import { ApiError, publicApi } from '../lib/api'
import type { PublicEvent, PublicPhoto } from '../lib/types'
import { removalRequestLink } from '../shared/privacy'
import { PhotoGrid } from './PhotoGrid'
import { PhotoViewer } from './PhotoViewer'

const CONSENT_TEXT =
  'Take a selfie to see only the photos you’re in. Your selfie is used once for this search and is not saved. Face data for this gallery is deleted when the gallery’s photos are deleted.'

type Step = 'idle' | 'consent' | 'searching' | 'results' | 'error'

/** The friendly sentence for a failed search. */
function searchErrorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === 'NO_FACE') return 'We couldn’t see a face clearly. Try again in good light and face the camera.'
    if (err.code === 'BAD_IMAGE') return 'We couldn’t read that photo. Please take a new selfie.'
    if (err.code === 'FACE_SEARCH_LIMIT' || err.status === 429) return 'Too many searches. Try again in a while.'
    if (err.code === 'FACE_SEARCH_OFF') return 'Find my photos isn’t available for this gallery.'
    if (err.status === 413) return 'That photo is too large. Please take a new selfie.'
  }
  return 'Something went wrong. Please check your connection and try again.'
}

export function FaceIcon({ className = 'size-5' }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" className={className} aria-hidden="true">
      <path d="M4 8V6a2 2 0 0 1 2-2h2M16 4h2a2 2 0 0 1 2 2v2M20 16v2a2 2 0 0 1-2 2h-2M8 20H6a2 2 0 0 1-2-2v-2" />
      <circle cx="12" cy="10.5" r="3" />
      <path d="M7.5 18c.9-2.2 2.6-3.2 4.5-3.2s3.6 1 4.5 3.2" />
    </svg>
  )
}

/**
 * "Find my photos": a guest takes a selfie and sees only the photos they are in. Consent first (the selfie is used
 * once and never saved), then the camera, then a results page that reuses the photo grid and viewer. Shown by the
 * gallery only when the studio bought face search for the event.
 */
export function FindMyPhotos({ event, instagram, onActivity, liked, onToggleLike, onRequestHd }: {
  event: PublicEvent
  instagram?: string | null
  onActivity?: (type: 'PHOTO_VIEW' | 'DOWNLOAD' | 'SHARE' | 'INSTAGRAM', photoId?: string) => void
  liked: (photoId: string) => boolean
  onToggleLike: (photoId: string) => void
  onRequestHd: (photoId: string, name: string, contact: string) => Promise<void>
}) {
  const [step, setStep] = useState<Step>('idle')
  const [agreed, setAgreed] = useState(false)
  const [consented, setConsented] = useState(false)
  const [photos, setPhotos] = useState<PublicPhoto[]>([])
  const [message, setMessage] = useState('')
  const [selected, setSelected] = useState<number>(-1)
  const input = useRef<HTMLInputElement>(null)
  const opener = useRef<HTMLButtonElement>(null)

  const full = step === 'searching' || step === 'results' || step === 'error'
  useEffect(() => {
    if (!full) return
    const old = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = old
    }
  }, [full])

  const close = () => {
    setStep('idle')
    setPhotos([])
    setSelected(-1)
    opener.current?.focus()
  }
  const pick = () => input.current?.click()
  const start = () => {
    if (consented) pick()
    else setStep('consent')
  }
  const confirm = () => {
    if (!agreed) return
    setConsented(true)
    setStep('idle')
    pick()
  }

  const onFile = async (file: File | undefined) => {
    if (input.current) input.current.value = '' // lets the same file be chosen again
    if (!file) return
    setStep('searching')
    try {
      const selfie = await prepareSelfie(file)
      const result = await publicApi.findMe(event.slug, selfie)
      setPhotos(result.photos)
      setStep('results')
    } catch (err) {
      setMessage(searchErrorMessage(err))
      setStep('error')
    }
  }

  const ghost = 'inline-flex h-10 items-center justify-center rounded-full border border-line bg-white px-4 text-sm font-medium text-ink'
  const solid = 'inline-flex h-10 items-center justify-center rounded-full bg-ink px-5 text-sm font-medium text-white disabled:opacity-40'

  return (
    <>
      <div className="mx-3 mt-3 sm:mx-auto sm:max-w-xl">
        <button ref={opener} type="button" onClick={start} data-testid="find-my-photos"
          className="flex w-full items-center gap-3 rounded-2xl bg-ink px-4 py-3 text-left text-white shadow-[0_6px_20px_rgba(0,0,0,0.12)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#b37a2c]">
          <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-white/15"><FaceIcon className="size-6" /></span>
          <span className="min-w-0 flex-1">
            <span className="block text-sm font-semibold">Find my photos</span>
            <span className="block text-xs text-white/75">Take a selfie to see only the photos you’re in</span>
          </span>
          <span aria-hidden="true" className="text-white/70">→</span>
        </button>
        <input ref={input} type="file" accept="image/*" capture="user" className="sr-only" tabIndex={-1} aria-label="Take a selfie"
          data-testid="selfie-input" onChange={(e) => void onFile(e.target.files?.[0])} />
      </div>

      {step === 'consent' && (
        <div className="anim-fade-in fixed inset-0 z-[60] flex items-end justify-center bg-black/50 sm:items-center sm:p-4" onKeyDown={(e) => e.key === 'Escape' && setStep('idle')}>
          <div role="dialog" aria-modal="true" aria-labelledby="fmp-title" className="anim-sheet-up w-full max-w-md rounded-t-2xl bg-white p-5 shadow-2xl sm:rounded-2xl">
            <div className="flex items-start gap-3">
              <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-stone-100 text-ink"><FaceIcon className="size-6" /></span>
              <div>
                <h2 id="fmp-title" className="text-lg font-semibold text-ink">Find my photos</h2>
                <p className="mt-1 text-sm text-muted">{CONSENT_TEXT}</p>
              </div>
            </div>
            <label className="mt-4 flex items-start gap-3 text-sm text-ink">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} autoFocus className="mt-0.5 size-5 shrink-0 accent-[#b37a2c]" />
              <span>I agree</span>
            </label>
            <p className="mt-2 text-xs text-muted">
              Read how this works in the <a href="/privacy" target="_blank" rel="noreferrer" className="underline underline-offset-2">privacy notice</a>.
            </p>
            <div className="mt-5 flex gap-2">
              <button type="button" onClick={() => setStep('idle')} className={`${ghost} flex-1`}>Cancel</button>
              <button type="button" onClick={confirm} disabled={!agreed} className={`${solid} flex-1`}>Take a selfie</button>
            </div>
          </div>
        </div>
      )}

      {full && (
        <div className="fixed inset-0 z-50 overflow-y-auto bg-paper" role="region" aria-label="Photos of you">
          <header className="sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-line bg-paper/95 px-4 py-3 backdrop-blur">
            <h2 className="font-display text-xl text-ink" aria-live="polite">
              {step === 'results' ? `Photos of you (${photos.length})` : step === 'searching' ? 'Looking for you…' : 'Find my photos'}
            </h2>
            <button type="button" onClick={close} className={ghost}>Back to all photos</button>
          </header>

          {step === 'searching' && (
            <p role="status" className="px-8 py-24 text-center text-sm text-muted">Looking for the photos you’re in. This takes a few seconds.</p>
          )}

          {step === 'error' && (
            <Notice title={message} >
              <button type="button" onClick={pick} className={solid}>Try again</button>
              <button type="button" onClick={close} className={ghost}>Back to all photos</button>
            </Notice>
          )}

          {step === 'results' && photos.length === 0 && (
            <Notice title="No photos of you yet" body="New photos arrive during the event. Try again later.">
              <button type="button" onClick={pick} className={solid}>Search again</button>
              <button type="button" onClick={close} className={ghost}>Back to all photos</button>
            </Notice>
          )}

          {step === 'results' && photos.length > 0 && (
            <div className="pt-3 pb-12">
              <div className="mb-3 flex justify-center px-3">
                <button type="button" onClick={pick} className={ghost}>Search again</button>
              </div>
              <PhotoGrid photos={photos} onOpen={(p) => setSelected(photos.findIndex((x) => x.id === p.id))} onNearEnd={() => {}} hasMore={false} />
            </div>
          )}
        </div>
      )}

      {step === 'results' && selected >= 0 && photos[selected] && (
        <PhotoViewer
          photos={photos}
          index={selected}
          total={photos.length}
          eventSlug={event.slug}
          eventName={event.name}
          onIndexChange={setSelected}
          onClose={() => setSelected(-1)}
          onNearEnd={() => {}}
          instagram={instagram}
          onActivity={onActivity}
          liked={liked}
          onToggleLike={onToggleLike}
          onRequestHd={onRequestHd}
          actions={(p) => (
            <a href={removalRequestLink(event.name, p)}
              className="inline-flex h-10 items-center justify-center rounded-full px-4 text-xs text-white/70 underline-offset-4 hover:text-white hover:underline">
              Request removal
            </a>
          )}
        />
      )}
    </>
  )
}

function Notice({ title, body, children }: { title: string; body?: string; children: ReactNode }) {
  return (
    <div className="px-8 py-20 text-center" role="status">
      <p className="mx-auto max-w-sm font-display text-2xl text-ink">{title}</p>
      {body && <p className="mt-2 text-sm text-muted">{body}</p>}
      <div className="mt-6 flex flex-wrap justify-center gap-2">{children}</div>
    </div>
  )
}
