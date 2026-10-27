import { useEffect } from 'react'
import { Link, Navigate, NavLink, Outlet, Route, Routes, useLocation } from 'react-router'
import { adminApi } from '../lib/api'
import AccountPage from './AccountPage'
import { auth, useSession } from './auth'
import EventDashboardPage from './EventDashboardPage'
import EventsPage from './EventsPage'
import LoginPage from './LoginPage'
import NewEventPage from './NewEventPage'
import PrintQrPage from './PrintQrPage'
import StudiosPage from './StudiosPage'

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

function Layout() {
  const session = useSession()
  return (
    <div className="min-h-dvh bg-stone-50">
      <header className="no-print border-b border-line bg-white">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link to="/admin" className="flex items-baseline gap-2">
            <span className="font-display text-xl font-semibold">McreatiK</span>
            <span className="hidden text-xs tracking-[0.2em] text-muted uppercase sm:inline">Live Gallery</span>
          </Link>
          <nav className="flex items-center gap-3 text-sm whitespace-nowrap sm:gap-4">
            <NavLink to="/admin" end className={navClass}>Events</NavLink>
            {session?.role === 'SUPER_ADMIN' && <NavLink to="/admin/studios" className={navClass}>Studios</NavLink>}
            <NavLink to="/admin/account" className={navClass} title={session?.email}>
              <span className="hidden sm:inline">{session?.displayName}</span>
              <span className="sm:hidden">Account</span>
            </NavLink>
            <button type="button" onClick={auth.signOut} className="text-muted hover:text-ink">
              Sign out
            </button>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
        <Outlet />
      </main>
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
        </Route>
      </Route>
    </Routes>
  )
}
