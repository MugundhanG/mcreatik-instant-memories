import { useCallback, useEffect, useRef, useState } from 'react'

/** Longest edge of the captured selfie (the server and the quality check need no more). */
const MAX_EDGE = 1280

type CameraError = 'denied' | 'no-camera' | 'unsupported' | 'other'

const ERRORS: Record<CameraError, { title: string; body: string }> = {
  denied: {
    title: 'Allow the camera',
    body: 'Camera access is blocked for this page. Allow the camera in your browser’s site settings, then tap Try again.',
  },
  'no-camera': { title: 'No front camera found', body: 'Find my photos needs your phone’s front (selfie) camera.' },
  unsupported: {
    title: 'Open in Chrome or Safari',
    body: 'This browser can’t open the camera here. Copy the gallery link and open it in Chrome or Safari.',
  },
  other: { title: 'The camera didn’t start', body: 'Close other apps using the camera, then tap Try again.' },
}

function errorOf(err: unknown): CameraError {
  const name = err instanceof DOMException || err instanceof Error ? err.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'denied'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'no-camera'
  return 'other'
}

/**
 * The selfie camera for Find my photos: the front camera only, inside the page. There is no switch to the back
 * camera and no picking a saved photo, so a guest can only search for themselves. Shows a face guide, then the
 * picture with Retake / Use photo; `onCapture` gets a JPEG.
 */
export function SelfieCamera({ onCapture, onCancel }: { onCapture: (selfie: Blob) => void; onCancel: () => void }) {
  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const supported = typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
  const [failure, setError] = useState<CameraError | null>(null)
  const error: CameraError | null = supported ? failure : 'unsupported'
  const [ready, setReady] = useState(false)
  const [shot, setShot] = useState<{ blob: Blob; url: string } | null>(null)
  const [attempt, setAttempt] = useState(0)

  const stop = useCallback(() => {
    stream.current?.getTracks().forEach((t) => t.stop())
    stream.current = null
    setReady(false)
  }, [])

  useEffect(() => {
    if (shot) return
    let cancelled = false
    if (!supported) return
    navigator.mediaDevices
      .getUserMedia({ audio: false, video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 1280 } } })
      .then((s) => {
        if (cancelled) {
          s.getTracks().forEach((t) => t.stop())
          return
        }
        // A phone that only gave the back camera is refused: guests search only for themselves.
        const facing = s.getVideoTracks()[0]?.getSettings().facingMode
        if (facing === 'environment') {
          s.getTracks().forEach((t) => t.stop())
          setError('no-camera')
          return
        }
        stream.current = s
        if (video.current) {
          video.current.srcObject = s
          void video.current.play().catch(() => {})
        }
      })
      .catch((err) => !cancelled && setError(errorOf(err)))
    return () => {
      cancelled = true
      stop()
    }
  }, [shot, attempt, stop, supported])

  useEffect(() => () => {
    if (shot) URL.revokeObjectURL(shot.url)
  }, [shot])

  const capture = () => {
    const v = video.current
    if (!v || !v.videoWidth) return
    const scale = Math.min(1, MAX_EDGE / Math.max(v.videoWidth, v.videoHeight))
    const canvas = document.createElement('canvas')
    canvas.width = Math.round(v.videoWidth * scale)
    canvas.height = Math.round(v.videoHeight * scale)
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    // Saved as the guest sees themselves in the preview (mirrored), like a phone's own selfie camera.
    ctx.translate(canvas.width, 0)
    ctx.scale(-1, 1)
    ctx.drawImage(v, 0, 0, canvas.width, canvas.height)
    canvas.toBlob((blob) => {
      if (!blob) return
      stop()
      setShot({ blob, url: URL.createObjectURL(blob) })
    }, 'image/jpeg', 0.9)
  }

  const btn = 'inline-flex h-12 items-center justify-center rounded-full px-6 text-sm font-medium'

  return (
    <div className="fixed inset-0 z-[70] flex flex-col bg-black text-white" role="dialog" aria-modal="true" aria-label="Take a selfie" data-testid="selfie-camera">
      <div className="flex items-center justify-between px-4 pt-[max(env(safe-area-inset-top),0.75rem)] pb-3">
        <p className="text-sm font-medium">{shot ? 'Use this selfie?' : 'Take a selfie'}</p>
        <button type="button" onClick={onCancel} className="rounded-full px-3 py-1.5 text-sm text-white/80 hover:text-white">Cancel</button>
      </div>

      <div className="relative flex-1 overflow-hidden">
        {error ? (
          <div className="flex h-full flex-col items-center justify-center px-8 text-center" role="alert" data-testid="camera-error">
            <p className="font-display text-2xl">{ERRORS[error].title}</p>
            <p className="mt-2 max-w-sm text-sm text-white/75">{ERRORS[error].body}</p>
            {error !== 'unsupported' && (
              <button type="button" onClick={() => {
                setError(null)
                setAttempt((n) => n + 1)
              }} className={`${btn} mt-6 bg-white text-ink`}>Try again</button>
            )}
          </div>
        ) : shot ? (
          <img src={shot.url} alt="Your selfie" className="absolute inset-0 size-full object-cover" />
        ) : (
          <>
            <video ref={video} playsInline muted autoPlay onPlaying={() => setReady(true)}
              className="absolute inset-0 size-full -scale-x-100 object-cover" data-testid="camera-preview" />
            {/* Face guide: an oval cut-out over a dimmed frame. */}
            <div aria-hidden="true" className="pointer-events-none absolute inset-0 flex items-center justify-center">
              <div className="h-[58%] max-h-[440px] w-[68%] max-w-[330px] rounded-[50%] border-2 border-white/85 shadow-[0_0_0_9999px_rgba(0,0,0,0.45)]" />
            </div>
            <p className="pointer-events-none absolute inset-x-0 bottom-4 text-center text-sm text-white/85">
              {ready ? 'Fit your face in the oval, in good light' : 'Starting the camera…'}
            </p>
          </>
        )}
      </div>

      <div className="flex items-center justify-center gap-3 px-4 pt-4 pb-[max(env(safe-area-inset-bottom),1.25rem)]">
        {shot ? (
          <>
            <button type="button" onClick={() => setShot(null)} className={`${btn} border border-white/40 text-white`}>Retake</button>
            <button type="button" onClick={() => onCapture(shot.blob)} className={`${btn} bg-white text-ink`} data-testid="use-selfie">OK, find my photos</button>
          </>
        ) : !error && (
          <button type="button" onClick={capture} disabled={!ready} aria-label="Take photo" data-testid="shutter"
            className="size-[72px] rounded-full border-4 border-white bg-white/20 transition-transform active:scale-95 disabled:opacity-40" />
        )}
      </div>
    </div>
  )
}
