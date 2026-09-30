import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

afterEach(() => cleanup())

// jsdom lacks these browser APIs used by the gallery.
class NoopIntersectionObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
  takeRecords() { return [] }
}
Object.defineProperty(window, 'IntersectionObserver', { writable: true, value: NoopIntersectionObserver })
window.scrollTo = () => {}
