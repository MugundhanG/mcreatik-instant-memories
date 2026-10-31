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

/** Shutter with an optional caption; announced to screen readers as a loading status. */
export function ShutterLoader({ label = 'Loading', className = '' }: { label?: string; className?: string }) {
  return (
    <div role="status" aria-live="polite" className={`flex flex-col items-center gap-3 ${className}`} data-testid="shutter-loader">
      <ShutterIcon />
      <span className="text-[11px] tracking-[0.3em] text-muted uppercase">{label}</span>
    </div>
  )
}

/** Full-screen loader for a page (or the admin app) that is not ready yet. */
export function PageLoader({ label }: { label?: string }) {
  return (
    <main className="flex min-h-dvh items-center justify-center">
      <ShutterLoader label={label} />
    </main>
  )
}
