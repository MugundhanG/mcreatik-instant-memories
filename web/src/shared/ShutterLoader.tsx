import { useEffect, useState } from 'react'

/**
 * Camera-shutter loading animation: six aperture blades close and reopen while the iris turns.
 * Each blade is a half-plane whose inner edge is one side of the hexagonal opening; sliding the blades
 * toward the centre closes the shutter. index.html carries a static copy for the first paint before JS.
 */
const BLADES = [0, 60, 120, 180, 240, 300]

export function ShutterIcon({ className = 'size-14' }: { className?: string }) {
  return (
    <svg viewBox="0 0 100 100" className={`shutter ${className}`} aria-hidden="true">
      <defs>
        <clipPath id="shutter-lens">
          <circle cx="50" cy="50" r="44" />
        </clipPath>
      </defs>
      <g className="shutter-iris" clipPath="url(#shutter-lens)">
        {BLADES.map((deg) => (
          <g key={deg} transform={`rotate(${deg} 50 50)`}>
            <rect className="shutter-blade" x="-30" y="-80" width="160" height="102" fill="#1c1917" />
          </g>
        ))}
        {BLADES.map((deg) => (
          <g key={deg} transform={`rotate(${deg} 50 50)`}>
            <line className="shutter-blade" x1="50" y1="22" x2="150" y2="22" stroke="#c9a35a" strokeWidth="1.6" />
          </g>
        ))}
      </g>
      <circle cx="50" cy="50" r="45" fill="none" stroke="#b8873b" strokeWidth="3" />
    </svg>
  )
}

/**
 * A percentage for loads whose real progress the browser can't tell us (a page's data): it climbs quickly at first,
 * then slows, and never reaches 100 before the content replaces the loader.
 */
function useEstimatedPercent(active: boolean) {
  const [percent, setPercent] = useState(0)
  useEffect(() => {
    if (!active) return
    const started = Date.now()
    const tick = () => setPercent(Math.min(98, Math.round(98 * (1 - Math.exp(-(Date.now() - started) / 1400)))))
    const id = setInterval(tick, 120)
    return () => clearInterval(id)
  }, [active])
  return percent
}

/**
 * Shutter with an optional caption and the loading percentage below it; announced to screen readers as a loading
 * status. Pass {@code percent} when the real progress is known (an upload, a print file); otherwise it is estimated.
 */
export function ShutterLoader({ label = 'Loading', className = '', percent }: { label?: string; className?: string; percent?: number }) {
  const estimated = useEstimatedPercent(percent === undefined)
  const shown = Math.max(0, Math.min(100, Math.round(percent ?? estimated)))
  return (
    <div role="status" aria-live="polite" className={`flex flex-col items-center gap-3 ${className}`} data-testid="shutter-loader">
      <ShutterIcon />
      <span className="text-[11px] tracking-[0.3em] text-muted uppercase">{label}</span>
      <span className="-mt-1 text-sm font-medium tabular-nums text-ink" data-testid="loader-percent" aria-hidden="true">{shown}%</span>
    </div>
  )
}

/** Full-screen loader for a page (or the admin app) that is not ready yet. */
export function PageLoader({ label, percent }: { label?: string; percent?: number }) {
  return (
    <main className="flex min-h-dvh items-center justify-center">
      <ShutterLoader label={label} percent={percent} />
    </main>
  )
}
