import { useSyncExternalStore } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { demo } from './mockBackend'

/** Small floating control, demo build only: add a photo now, pause arrivals, back to the demo menu. */
export default function DemoBar() {
  const location = useLocation()
  const navigate = useNavigate()
  const auto = useSyncExternalStore(demo.subscribe, demo.isAuto)
  if (location.pathname === '/demo' || location.pathname.endsWith('/print')) return null

  const slug = location.pathname.startsWith('/e/') ? decodeURIComponent(location.pathname.slice(3)) : demo.mainSlug
  const btn = 'rounded-full px-3 py-1.5 hover:bg-white/15 focus-visible:outline-2 focus-visible:outline-white'

  return (
    <div className="no-print fixed bottom-[max(env(safe-area-inset-bottom),0.75rem)] left-1/2 z-40 flex -translate-x-1/2 items-center gap-1 rounded-full bg-ink/90 p-1 text-xs whitespace-nowrap text-white shadow-lg backdrop-blur">
      <span className="px-2.5 font-semibold tracking-[0.15em] uppercase opacity-70">Demo</span>
      <button type="button" className={btn} onClick={() => demo.addPhoto(slug)}>+ New photo</button>
      <button type="button" className={btn} onClick={() => demo.setAuto(!auto)}>{auto ? 'Pause arrivals' : 'Resume arrivals'}</button>
      <button type="button" className={btn} onClick={() => navigate('/demo')}>Menu</button>
    </div>
  )
}
