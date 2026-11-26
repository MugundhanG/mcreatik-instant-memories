import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { prepareSelfie } from '../admin/prepareUpload'
import { checkSelfie, SELFIE_TIPS } from '../admin/selfieCheck'
import type { SelfieIssue } from '../admin/selfieCheck'
import { visitorId } from '../lib/activity'
import { ApiError, publicApi } from '../lib/api'
import { clearMatchId, clearSelfie, lastCheck, loadMatchId, loadSelfie, markChecked, saveMatchId, saveSelfie } from '../lib/faceMatch'
import type { PublicEvent, PublicPhoto } from '../lib/types'
import { removalRequestLink } from '../shared/privacy'
import { FaceScan, SCAN_MIN_MS } from './FaceScan'
import { PhotoGrid } from './PhotoGrid'
import { PhotoViewer } from './PhotoViewer'
import { SelfieCamera } from './SelfieCamera'
import { useWatermarker } from '../lib/watermark'

const CONSENT_TEXT =
  'Take a selfie to see only the photos you’re in. Your selfie is sent once to find you and is not saved on our server. This phone then remembers your photos with a random code, so you never need another selfie; until your first photo is found, the selfie stays only on this phone to keep looking as new photos arrive. Tap “Forget me” any time to delete it all.'

type Step = 'idle' | 'consent' | 'camera' | 'searching' | 'tips' | 'results' | 'error'

/** After this many selfies that fail the quality check, the guest may send one anyway. */
const USE_ANYWAY_AFTER = 2
/** How often this page looks at the guest's photos (a free database lookup). */
const TICK_MS = 60_000
/** At most this often it asks the face engine for new photos of the guest (each costs a few searches). */
export const AUTO_UPDATE_MS = 15 * 60_000

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

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
 * "Find my photos": a guest takes a selfie with the front camera and sees only the photos they are in. Consent first,
 * then the camera, a face scan, and the guest's photos. The selfie is taken once: the phone remembers the match and
 * new photos of the guest appear by themselves. In a private gallery (`privateMode`) this is the whole page: guests
 * never see anyone else's photos.
 */
export function FindMyPhotos({ event, instagram, onActivity, liked, onToggleLike, onRequestHd, privateMode = false }: {
  event: PublicEvent
  instagram?: string | null
  onActivity?: (type: 'PHOTO_VIEW' | 'DOWNLOAD' | 'SHARE' | 'INSTAGRAM', photoId?: string) => void
  liked: (photoId: string) => boolean
  onToggleLike: (photoId: string) => void
  onRequestHd: (photoId: string, name: string, contact: string) => Promise<void>
  privateMode?: boolean
}) {
  const slug = event.slug
  const watermark = useWatermarker(event)
  const [step, setStep] = useState<Step>('idle')
  const [agreed, setAgreed] = useState(false)
  const [photos, setPhotos] = useState<PublicPhoto[]>([])
  const [message, setMessage] = useState('')
  const [selected, setSelected] = useState<number>(-1)
  // Remember me: the code kept on this phone, and whether the server knows it.
  const [matchId, setMatchId] = useState<string | null>(() => loadMatchId(slug))
  const [remembered, setRemembered] = useState(false)
  // Nothing matched yet: the selfie kept on this phone to look again by itself.
  const [waiting, setWaiting] = useState<boolean>(() => !loadMatchId(slug) && loadSelfie(slug) !== null)
  const justMade = useRef<string | null>(null) // a code from a search just now: its photos are already on screen
  const [scanning, setScanning] = useState<Blob | null>(null)
  // The selfie quality check: what was wrong, how many tries failed, and the picture to send if the guest insists.
  const [issues, setIssues] = useState<SelfieIssue[]>([])
  const [failedChecks, setFailedChecks] = useState(0)
  const [held, setHeld] = useState<Blob | null>(null)
  const opener = useRef<HTMLButtonElement>(null)
  const checking = useRef(false)

  const known = remembered || waiting // the guest took their selfie already: never ask again
  const overlay = step === 'searching' || step === 'tips' || step === 'error' || (step === 'results' && !privateMode)
  useEffect(() => {
    if (!overlay) return
    const old = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = old
    }
  }, [overlay])

  const forgetLocal = useCallback(() => {
    clearMatchId(slug)
    clearSelfie(slug)
    setMatchId(null)
    setRemembered(false)
    setWaiting(false)
    setPhotos([])
  }, [slug])

  // On opening the gallery: a stored match shows "Photos of you" again, without a selfie and without AWS (a database lookup).
  useEffect(() => {
    if (!matchId || justMade.current === matchId) return
    let active = true
    publicApi.match(slug, matchId).then((m) => {
      if (!active) return
      setPhotos(m.photos)
      setRemembered(true)
    }).catch((err) => {
      if (active && err instanceof ApiError && err.status === 404) forgetLocal() // unknown or expired: start over
    })
    return () => {
      active = false
    }
  }, [matchId, slug, forgetLocal])

  /** Keeps a found match (and drops the kept selfie), or keeps the selfie to look again later. */
  const keep = useCallback(async (resultMatchId: string | null, selfie: Blob) => {
    markChecked(slug)
    if (resultMatchId) {
      saveMatchId(slug, resultMatchId)
      clearSelfie(slug)
      justMade.current = resultMatchId
      setMatchId(resultMatchId)
      setRemembered(true)
      setWaiting(false)
    } else {
      await saveSelfie(slug, selfie)
      setWaiting(true)
    }
  }, [slug])

  // New photos of the guest appear by themselves: the match is read every minute (free), and the engine is asked for
  // new photos at most every AUTO_UPDATE_MS, only while the page is on screen and only when photos were added.
  useEffect(() => {
    if (!known) return
    const check = async () => {
      if (checking.current || document.visibilityState !== 'visible') return
      checking.current = true
      try {
        const due = Date.now() - lastCheck(slug) >= AUTO_UPDATE_MS
        if (matchId) {
          let m = await publicApi.match(slug, matchId)
          if (m.hasNew && due) {
            markChecked(slug)
            m = await publicApi.refreshMatch(slug, matchId, visitorId())
          }
          setPhotos(m.photos)
        } else if (due) {
          const selfie = loadSelfie(slug)
          if (!selfie) {
            setWaiting(false)
            return
          }
          markChecked(slug)
          const result = await publicApi.findMe(slug, selfie, visitorId())
          setPhotos(result.photos)
          await keep(result.matchId ?? null, selfie)
        }
      } catch (err) {
        if (err instanceof ApiError && err.status === 404 && err.code === 'MATCH_NOT_FOUND') forgetLocal()
        // anything else: the next tick tries again
      } finally {
        checking.current = false
      }
    }
    void check()
    const timer = setInterval(() => void check(), TICK_MS)
    const onVisible = () => void check()
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [known, matchId, slug, keep, forgetLocal])

  const close = () => {
    setStep('idle')
    setSelected(-1)
    setFailedChecks(0)
    setHeld(null)
    opener.current?.focus()
  }

  const forgetMe = () => {
    const id = matchId
    forgetLocal()
    close()
    if (id) publicApi.forgetMatch(slug, id).catch(() => {})
  }

  const start = () => {
    if (known) setStep('results')
    else setStep(agreed ? 'camera' : 'consent')
  }

  const search = async (selfie: Blob) => {
    setStep('searching')
    setScanning(selfie)
    try {
      // The face scan plays at least SCAN_MIN_MS, alongside the real search (never an extra wait after it).
      const [result] = await Promise.all([publicApi.findMe(slug, selfie, visitorId()), delay(SCAN_MIN_MS)])
      setPhotos(result.photos)
      setFailedChecks(0)
      setHeld(null)
      await keep(result.matchId ?? null, selfie)
      setStep(privateMode ? 'idle' : 'results')
    } catch (err) {
      setMessage(searchErrorMessage(err))
      setStep('error')
    } finally {
      setScanning(null)
    }
  }

  const onCapture = async (shot: Blob) => {
    setStep('searching')
    let selfie: Blob
    try {
      selfie = await prepareSelfie(new File([shot], 'selfie.jpg', { type: 'image/jpeg' }))
    } catch {
      selfie = shot
    }
    // Quick look on the phone first: a bad selfie costs nothing to retake, a search does.
    const check = await checkSelfie(selfie)
    if (!check.ok) {
      setHeld(selfie)
      setIssues(check.issues)
      setFailedChecks((n) => n + 1)
      setStep('tips')
      return
    }
    await search(selfie)
  }

  const ghost = 'inline-flex h-10 items-center justify-center rounded-full border border-line bg-white px-4 text-sm font-medium text-ink'
  const solid = 'inline-flex h-10 items-center justify-center rounded-full bg-ink px-5 text-sm font-medium text-white disabled:opacity-40'
  const backLabel = privateMode ? 'Close' : 'Back to all photos'

  const emptyNotice = (
    <Notice title="No photos of you yet" body="We’ll keep looking as new photos arrive, all by itself. No new selfie needed: just come back later.">
      <button type="button" onClick={forgetMe} className={ghost}>Forget me</button>
    </Notice>
  )

  const results = (
    <div className="pt-3 pb-12" data-testid="my-photo-results">
      <p className="mb-3 px-6 text-center text-sm text-muted">New photos of you appear here by themselves.</p>
      <PhotoGrid photos={photos} onOpen={(p) => setSelected(photos.findIndex((x) => x.id === p.id))} onNearEnd={() => {}} hasMore={false} />
      <p className="mt-6 text-center">
        <button type="button" onClick={forgetMe} className="text-xs text-muted underline underline-offset-4 hover:text-ink">Forget me (delete my match from this phone and the server)</button>
      </p>
    </div>
  )

  return (
    <>
      {privateMode ? (
        <section className="mx-3 mt-3 sm:mx-auto sm:max-w-xl" data-testid="private-gallery">
          {!known && (
            <div className="rounded-2xl bg-white p-5 text-center shadow-[0_6px_20px_rgba(0,0,0,0.08)]">
              <span className="mx-auto flex size-14 items-center justify-center rounded-full bg-stone-100 text-ink"><FaceIcon className="size-8" /></span>
              <h2 className="mt-3 font-display text-2xl text-ink">See the photos you’re in</h2>
              <p className="mt-1 text-sm text-muted">This gallery is private. Take a selfie and you’ll see only the photos you appear in.</p>
              <button ref={opener} type="button" onClick={start} data-testid="find-my-photos" className={`${solid} mt-4 h-12 px-6`}>
                <FaceIcon className="mr-2 size-5" /> Take a selfie
              </button>
            </div>
          )}
        </section>
      ) : (
        <div className="mx-3 mt-3 sm:mx-auto sm:max-w-xl">
          <button ref={opener} type="button" onClick={start} data-testid={known ? 'my-photos' : 'find-my-photos'}
            className="flex w-full items-center gap-3 rounded-2xl bg-ink px-4 py-3 text-left text-white shadow-[0_6px_20px_rgba(0,0,0,0.12)] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[#b37a2c]">
            <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-white/15"><FaceIcon className="size-6" /></span>
            <span className="min-w-0 flex-1">
              <span className="block text-sm font-semibold">{known ? `Photos of you (${photos.length})` : 'Find my photos'}</span>
              <span className="block text-xs text-white/75">
                {known ? 'New photos of you appear by themselves.' : 'Take a selfie to see only the photos you’re in'}
              </span>
            </span>
            <span aria-hidden="true" className="text-white/70">→</span>
          </button>
        </div>
      )}

      {/* A private gallery's page is the guest's own photos. */}
      {privateMode && known && step !== 'searching' && (
        <section className="mt-2" aria-label="Photos of you">
          <h2 className="px-4 pt-2 text-center font-display text-2xl text-ink">Photos of you ({photos.length})</h2>
          {photos.length === 0 ? emptyNotice : results}
        </section>
      )}

      {step === 'consent' && (
        <div className="anim-fade-in fixed inset-0 z-[60] flex items-end justify-center bg-black/50 sm:items-center sm:p-4" onKeyDown={(e) => e.key === 'Escape' && setStep('idle')}>
          <div role="dialog" aria-modal="true" aria-labelledby="fmp-title" className="anim-sheet-up max-h-[95dvh] w-full max-w-md overflow-y-auto rounded-t-2xl bg-white p-5 shadow-2xl sm:rounded-2xl">
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
              <button type="button" onClick={() => agreed && setStep('camera')} disabled={!agreed} className={`${solid} flex-1`}>Open the camera</button>
            </div>
          </div>
        </div>
      )}

      {step === 'camera' && <SelfieCamera onCancel={close} onCapture={(b) => void onCapture(b)} />}

      {overlay && (
        <div className="fixed inset-0 z-50 overflow-y-auto bg-paper" role="region" aria-label="Photos of you">
          <header className="sticky top-0 z-10 flex items-center justify-between gap-3 border-b border-line bg-paper/95 px-4 py-3 backdrop-blur">
            <h2 className="font-display text-xl text-ink" aria-live="polite">
              {step === 'results' ? `Photos of you (${photos.length})` : step === 'searching' ? 'Looking for you…' : 'Find my photos'}
            </h2>
            {step !== 'searching' && <button type="button" onClick={close} className={ghost}>{backLabel}</button>}
          </header>

          {step === 'searching' && <FaceScan selfie={scanning} />}

          {step === 'tips' && (
            <Notice title="Let’s get a clearer selfie" body="A clear selfie finds more of your photos.">
              <ul className="mx-auto mb-1 w-full max-w-sm space-y-2 text-left text-sm text-ink" data-testid="selfie-tips">
                {issues.map((i) => (
                  <li key={i} className="rounded-xl border border-line bg-white px-4 py-3">{SELFIE_TIPS[i]}</li>
                ))}
              </ul>
              <div className="flex w-full flex-wrap justify-center gap-2">
                <button type="button" onClick={() => setStep('camera')} className={solid}>Retake</button>
                {failedChecks >= USE_ANYWAY_AFTER && held && (
                  <button type="button" onClick={() => void search(held)} className={ghost}>Use anyway</button>
                )}
              </div>
            </Notice>
          )}

          {step === 'error' && (
            <Notice title={message}>
              <button type="button" onClick={() => setStep('camera')} className={solid}>Try again</button>
              <button type="button" onClick={close} className={ghost}>{backLabel}</button>
            </Notice>
          )}

          {step === 'results' && (photos.length === 0 ? emptyNotice : results)}
        </div>
      )}

      {selected >= 0 && photos[selected] && (step === 'results' || privateMode) && (
        <PhotoViewer
          photos={photos}
          index={selected}
          total={photos.length}
          eventSlug={slug}
          eventName={event.name}
          onIndexChange={setSelected}
          onClose={() => setSelected(-1)}
          onNearEnd={() => {}}
          instagram={instagram}
          studioName={event.brand?.name}
          onActivity={onActivity}
          liked={liked}
          onToggleLike={onToggleLike}
          onRequestHd={onRequestHd}
          matchId={matchId}
          watermark={watermark}
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
      {body && <p className="mx-auto mt-2 max-w-sm text-sm text-muted">{body}</p>}
      <div className="mt-6 flex flex-wrap justify-center gap-2">{children}</div>
    </div>
  )
}
