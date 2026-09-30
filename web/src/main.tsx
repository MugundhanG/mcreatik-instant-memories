import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter'
import '@fontsource/cormorant-garamond/500.css'
import '@fontsource/cormorant-garamond/600.css'
import './index.css'
import App from './App'

if (import.meta.env.VITE_DEMO === '1') {
  const { installDemoBackend } = await import('./demo/mockBackend')
  installDemoBackend()
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
