import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, MemoryRouter, Navigate, Route, Routes } from 'react-router'
import GalleryPage from './gallery/GalleryPage'
import { PageLoader } from './shared/ShutterLoader'

// Guests never download admin code.
const AdminApp = lazy(() => import('./admin/AdminApp'))
// The couple's portal (their own login): separate chunk too.
const ClientApp = lazy(() => import('./client/ClientApp'))
const PrivacyPage = lazy(() => import('./shared/PrivacyPage'))
// Demo build only (see src/demo). Inlined env check so production builds drop these chunks entirely.
const IS_DEMO = import.meta.env.VITE_DEMO === '1'
const DemoHome = IS_DEMO ? lazy(() => import('./demo/DemoHome')) : null
const DemoBar = IS_DEMO ? lazy(() => import('./demo/DemoBar')) : null

function Router({ children }: { children: ReactNode }) {
  // The demo runs inside a hosted preview frame with no address bar, so it keeps routes in memory.
  return IS_DEMO ? <MemoryRouter initialEntries={['/demo']}>{children}</MemoryRouter> : <BrowserRouter>{children}</BrowserRouter>
}

export default function App() {
  return (
    <Router>
      <Routes>
        <Route path="/e/:slug" element={<GalleryPage />} />
        <Route
          path="/privacy"
          element={
            <Suspense fallback={<PageLoader />}>
              <PrivacyPage />
            </Suspense>
          }
        />
        <Route
          path="/admin/*"
          element={
            <Suspense fallback={<PageLoader />}>
              <AdminApp />
            </Suspense>
          }
        />
        <Route
          path="/client/*"
          element={
            <Suspense fallback={<PageLoader />}>
              <ClientApp />
            </Suspense>
          }
        />
        {DemoHome && (
          <Route
            path="/demo"
            element={
              <Suspense fallback={<PageLoader />}>
                <DemoHome />
              </Suspense>
            }
          />
        )}
        <Route path="*" element={<Navigate to={IS_DEMO ? '/demo' : '/admin'} replace />} />
      </Routes>
      {DemoBar && (
        <Suspense fallback={null}>
          <DemoBar />
        </Suspense>
      )}
    </Router>
  )
}
