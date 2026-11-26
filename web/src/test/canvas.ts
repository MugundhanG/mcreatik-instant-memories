import { vi } from 'vitest'

/** jsdom has no canvas: a context that accepts every drawing call and records the text drawn. */
export function fakeCanvas() {
  const texts: string[] = []
  const store: Record<string | symbol, unknown> = {}
  const ctx = new Proxy(store, {
    get(t, k) {
      if (k in t) return t[k]
      if (k === 'measureText') return (s: string) => ({ width: s.length * 10 })
      if (k === 'fillText') return (s: string) => { texts.push(s) }
      if (k === 'createLinearGradient') return () => ({ addColorStop: () => {} })
      return () => {}
    },
    set(t, k, v) {
      t[k] = v
      return true
    },
  })
  const spy = vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(ctx as unknown as CanvasRenderingContext2D)
  return { texts, spy }
}
