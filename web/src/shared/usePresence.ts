import { useEffect, useState } from 'react'

const reducedMotion = () =>
  typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches

/**
 * Keeps something on screen for a moment after it closes, so it can play its exit animation:
 *   const { mounted, closing } = usePresence(open)
 *   {mounted && <div className={closing ? 'anim-pop-out' : 'anim-pop-in'} aria-hidden={closing || undefined} inert={closing}>…}
 * While closing it should be aria-hidden and inert, so it no longer counts as open (for screen readers and tests).
 */
export function usePresence(open: boolean, ms = 160) {
  const [shown, setShown] = useState(open)
  // Opening is immediate (derived during render, React's pattern for state that follows a prop).
  if (open && !shown) setShown(true)
  useEffect(() => {
    if (open || !shown) return
    const timer = setTimeout(() => setShown(false), reducedMotion() ? 0 : ms)
    return () => clearTimeout(timer)
  }, [open, shown, ms])
  return { mounted: open || shown, closing: !open && shown }
}
