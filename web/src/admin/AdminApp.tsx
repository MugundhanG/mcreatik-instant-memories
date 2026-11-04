import { lazy, Suspense, useEffect, useState } from 'react'
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation } from 'react-router'
import { adminApi } from '../lib/api'
import mkLogo from '../assets/mk-logo.webp'
import { PageLoader } from '../shared/ShutterLoader'
import { usePresence } from '../shared/usePresence'
import AccountPage from './AccountPage'
import { auth, useSession } from './auth'
import EventDashboardPage from './EventDashboardPage'
import EventsPage from './EventsPage'
import { DownloadIcon } from './icons'
import InstallUploaderPage from './InstallUploaderPage'
import LoginPage from './LoginPage'
import NewEventPage from './NewEventPage'
import PrintQrPage from './PrintQrPage'
import StudiosPage from './StudiosPage'

// McreatiK's documentation (diagrams, Markdown) is a separate download, fetched only when opened.
const DocsPage = lazy(() => import('./docs/DocsPage'))

function RequireSession() {
  const session = useSession()
  const location = useLocation()
  // Refresh role / name / password state (sessions saved before studio accounts had no role).
  const token = session?.token
  useEffect(() => {
    if (!token) return
    adminApi.account().then((a) => auth.update(a)).catch(() => {})
  }, [token])
  if (!session) return <Navigate to="/admin/login" replace state={{ from: location.pathname }} />
  // A studio with a temporary password chooses its own before anything else.
  if (session.mustChangePassword && location.pathname !== '/admin/account') return <Navigate to="/admin/account" replace />
  return <Outlet />
}

const navClass = ({ isActive }: { isActive: boolean }) => (isActive ? 'text-ink font-medium' : 'text-muted hover:text-ink')

/** `wide`: the documentation uses the full width for its sidebars. */
function Layout({ wide = false }: { wide?: boolean }) {
  const session = useSession()
  const superAdmin = session?.role === 'SUPER_ADMIN'
  const [menuOpen, setMenuOpen] = useState(false)
  const sheet = usePresence(menuOpen, 150)
  const phoneLinks = [
    { to: '/admin', label: 'Events' },
    { to: '/admin/install', label: 'Install uploader' },
    ...(superAdmin ? [{ to: '/admin/studios', label: 'Studios' }, { to: '/admin/docs', label: 'Docs' }] : []),
    { to: '/admin/account', label: session?.displayName ? `Account · ${session.displayName}` : 'Account' },
  ]
  return (
    <div className="min-h-dvh bg-stone-50">
      <header className="no-print border-b border-line bg-white">
        <div className={`mx-auto flex h-14 ${wide ? 'max-w-[90rem]' : 'max-w-6xl'} items-center justify-between px-4 sm:px-6`}>
          <Link to="/admin" className="flex items-center gap-2">
            <img src={mkLogo} alt="" className="h-7 w-auto" width={44} height={28} />
            <span className="font-display text-xl font-semibold">McreatiK</span>
            <span className="hidden text-xs tracking-[0.2em] text-muted uppercase sm:inline">Instant Memories</span>
          </Link>
          <nav aria-label="Main" className="hidden items-center gap-4 text-sm whitespace-nowrap sm:flex">
            <NavLink to="/admin" end className={navClass}>Events</NavLink>
            <NavLink to="/admin/install" title="Install McreatiK Instant Memories Uploader on a Windows or Mac laptop"
              className={({ isActive }) => `inline-flex h-8 items-center gap-1.5 rounded-lg border px-3 font-medium transition-colors ${
                isActive ? 'border-ink bg-ink text-white' : 'border-line text-ink hover:border-stone-400 hover:bg-stone-50'}`}>
              <DownloadIcon className="size-3.5" />
              Install uploader
            </NavLink>
            {superAdmin && <NavLink to="/admin/studios" className={navClass}>Studios</NavLink>}
            {superAdmin && <NavLink to="/admin/docs" className={navClass}>Docs</NavLink>}
            <NavLink to="/admin/account" className={navClass} title={session?.email}>{session?.displayName}</NavLink>
            <button type="button" onClick={auth.signOut} className="text-muted hover:text-ink">
              Sign out
            </button>
          </nav>
          {/* Phones: one Menu button instead of five links squeezed into the bar. */}
          <button type="button" onClick={() => setMenuOpen((o) => !o)} aria-expanded={menuOpen} aria-controls="phone-menu"
            className="flex h-9 items-center gap-2 rounded-lg border border-line px-3 text-sm sm:hidden">
            Menu
            <svg viewBox="0 0 16 16" className="size-4" aria-hidden="true">
              <path d={menuOpen ? 'M4 4l8 8M12 4l-8 8' : 'M2.5 4.5h11M2.5 8h11M2.5 11.5h11'} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </div>
        {sheet.mounted && (
          <nav id="phone-menu" aria-label="Main" aria-hidden={sheet.closing || undefined} inert={sheet.closing}
            className={`border-t border-line px-4 py-2 sm:hidden ${sheet.closing ? 'anim-drop-out' : 'anim-drop-in'}`}>
            {phoneLinks.map((l) => (
              <NavLink key={l.to} to={l.to} end={l.to === '/admin'} onClick={() => setMenuOpen(false)}
                className={({ isActive }) => `block rounded-lg px-3 py-3 text-base ${isActive ? 'bg-stone-100 font-medium text-ink' : 'text-stone-700'}`}>
                {l.label}
              </NavLink>
            ))}
            <button type="button" onClick={auth.signOut} className="block w-full rounded-lg px-3 py-3 text-left text-base text-stone-700">
              Sign out
            </button>
          </nav>
        )}
      </header>
      {wide ? (
        <Suspense fallback={<PageLoader />}>
          <Outlet />
        </Suspense>
      ) : (
        <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
          <Outlet />
        </main>
      )}
    </div>
  )
}

export default function AdminApp() {
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route element={<RequireSession />}>
        <Route path="events/:eventId/print" element={<PrintQrPage />} />
        <Route element={<Layout />}>
          <Route index element={<EventsPage />} />
          <Route path="events/new" element={<NewEventPage />} />
          <Route path="events/:eventId" element={<EventDashboardPage />} />
          <Route path="studios" element={<StudiosPage />} />
          <Route path="account" element={<AccountPage />} />
          <Route path="install" element={<InstallUploaderPage />} />
        </Route>
        <Route element={<Layout wide />}>
          <Route path="docs" element={<Navigate to="/admin/docs/overview" replace />} />
          <Route path="docs/:slug" element={<DocsPage />} />
        </Route>
      </Route>
    </Routes>
  )
}
