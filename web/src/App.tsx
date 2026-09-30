import { lazy, Suspense, type ReactNode } from 'react'
import { BrowserRouter, MemoryRouter, Navigate, Route, Routes } from 'react-router'
import GalleryPage from './gallery/GalleryPage'

// Guests never download admin code.
const AdminApp = lazy(() => import('./admin/AdminApp'))
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
          path="/admin/*"
          element={
            <Suspense fallback={null}>
              <AdminApp />
            </Suspense>
          }
        />
        {DemoHome && (
          <Route
            path="/demo"
            element={
              <Suspense fallback={null}>
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
