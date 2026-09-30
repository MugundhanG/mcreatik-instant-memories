import { Link, Navigate, Outlet, Route, Routes, useLocation } from 'react-router'
import { auth, useSession } from './auth'
import EventDashboardPage from './EventDashboardPage'
import EventsPage from './EventsPage'
import LoginPage from './LoginPage'
import NewEventPage from './NewEventPage'
import PrintQrPage from './PrintQrPage'

function RequireSession() {
  const session = useSession()
  const location = useLocation()
  if (!session) return <Navigate to="/admin/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}

function Layout() {
  const session = useSession()
  return (
    <div className="min-h-dvh bg-stone-50">
      <header className="no-print border-b border-line bg-white">
        <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link to="/admin" className="flex items-baseline gap-2">
            <span className="font-display text-xl font-semibold">McreatiK</span>
            <span className="text-xs tracking-[0.2em] text-muted uppercase">Live Gallery</span>
          </Link>
          <div className="flex items-center gap-3 text-sm">
            <span className="hidden text-muted sm:inline">{session?.email}</span>
            <button type="button" onClick={auth.signOut} className="text-muted hover:text-ink">
              Sign out
            </button>
          </div>
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
        </Route>
      </Route>
    </Routes>
  )
}
