import { useNavigate } from 'react-router'
import { auth } from '../admin/auth'
import { adminApi } from '../lib/api'
import { demo } from './mockBackend'

export default function DemoHome() {
  const navigate = useNavigate()

  const openAdmin = async () => {
    auth.signIn(await adminApi.login('demo@mcreatik.com', 'demo'))
    navigate('/admin')
  }

  return (
    <main className="mx-auto flex min-h-dvh max-w-3xl flex-col justify-center px-5 py-12">
      <p className="text-[11px] font-medium tracking-[0.3em] text-muted uppercase">McreatiK Studios · Interactive demo</p>
      <h1 className="mt-4 font-display text-5xl leading-[1.05] font-medium text-balance sm:text-6xl">Live Gallery</h1>
      <p className="mt-4 max-w-xl text-base text-muted">
        Guests scan a QR code and watch the photographers’ photos arrive during the event. Try both sides below. New demo
        photos arrive every few seconds, like at a real wedding.
      </p>

      <div className="mt-10 grid gap-4 sm:grid-cols-2">
        <button
          type="button"
          onClick={() => navigate(`/e/${demo.mainSlug}`)}
          className="group rounded-2xl border border-line bg-white p-6 text-left transition-shadow hover:shadow-md focus-visible:outline-2 focus-visible:outline-ink"
        >
          <p className="text-xs tracking-[0.2em] text-muted uppercase">What guests see</p>
          <p className="mt-2 font-display text-3xl">Guest gallery</p>
          <p className="mt-2 text-sm text-muted">The page a guest opens after scanning the QR at “Arun &amp; Priya Wedding”. Best on your phone.</p>
          <p className="mt-5 text-sm font-medium">Open gallery →</p>
        </button>
        <button
          type="button"
          onClick={openAdmin}
          className="group rounded-2xl border border-line bg-white p-6 text-left transition-shadow hover:shadow-md focus-visible:outline-2 focus-visible:outline-ink"
        >
          <p className="text-xs tracking-[0.2em] text-muted uppercase">What you see</p>
          <p className="mt-2 font-display text-3xl">Photographer dashboard</p>
          <p className="mt-2 text-sm text-muted">Events, cameras online, photo counts, QR poster. Try adding an uploader or an event.</p>
          <p className="mt-5 text-sm font-medium">Open dashboard →</p>
        </button>
      </div>

      <div className="mt-10 space-y-2 border-t border-line pt-6 text-sm text-muted">
        <p>Camera 3 drops offline for 30 seconds now and then. Its photos wait on the laptop and arrive when it reconnects.</p>
        <p>All photos here are generated demo images. Downloading, printing and the phone share sheet are turned off in this preview; they work in the live product.</p>
      </div>
    </main>
  )
}
