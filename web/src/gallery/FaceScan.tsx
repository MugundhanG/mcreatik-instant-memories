import { useEffect, useMemo, useState } from 'react'

/** The face scan shows at least this long, even when the search is quicker, so it reads as a real moment. */
export const SCAN_MIN_MS = 1600

const STEPS = ['Analyzing your face…', 'Matching with the gallery…', 'Finding your photos…']

/**
 * Shown while a selfie is searched: the selfie in a circle with a sweeping scan line, a rotating ring and pulsing
 * face points. Purely visual; the search itself runs alongside it.
 */
export function FaceScan({ selfie }: { selfie: Blob | null }) {
  const url = useMemo(() => (selfie ? URL.createObjectURL(selfie) : null), [selfie])
  const [step, setStep] = useState(0)

  useEffect(() => () => {
    if (url) URL.revokeObjectURL(url)
  }, [url])

  useEffect(() => {
    const t = setInterval(() => setStep((s) => Math.min(s + 1, STEPS.length - 1)), 700)
    return () => clearInterval(t)
  }, [])

  // Face points around the eyes, nose and mouth (percent of the circle).
  const points = [[36, 40], [64, 40], [50, 52], [40, 66], [60, 66], [50, 70], [30, 50], [70, 50]]

  return (
    <div className="flex flex-col items-center px-8 py-16 text-center" role="status" aria-live="polite" data-testid="face-scan">
      <div className="relative size-56 sm:size-64">
        <div className="face-scan-ring absolute -inset-3 rounded-full border-2 border-transparent border-t-[#b37a2c] border-r-[#e0a64f]" aria-hidden="true" />
        <div className="absolute inset-0 overflow-hidden rounded-full bg-stone-200 shadow-[0_10px_40px_rgba(179,122,44,0.25)]">
          {url && <img src={url} alt="" className="size-full object-cover" />}
          <div className="absolute inset-0 bg-[#b37a2c]/10" aria-hidden="true" />
          <div aria-hidden="true" className="face-scan-line absolute inset-x-0 top-0 h-10 bg-[linear-gradient(180deg,transparent,rgba(224,166,79,0.75),transparent)] [--scan-travel:13rem] sm:[--scan-travel:15rem]" />
          {points.map(([x, y], i) => (
            <span key={i} aria-hidden="true" className="face-scan-dot absolute size-2 rounded-full bg-white shadow-[0_0_8px_rgba(224,166,79,0.9)]"
              style={{ left: `${x}%`, top: `${y}%`, animationDelay: `${i * 120}ms` }} />
          ))}
        </div>
      </div>
      <p className="mt-8 font-display text-2xl text-ink">{STEPS[step]}</p>
      <p className="mt-2 text-sm text-muted">This takes a few seconds.</p>
    </div>
  )
}
