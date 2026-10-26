import { useCallback, useEffect, useRef, useState } from 'react'
import type { PointerEvent as ReactPointerEvent, RefObject, WheelEvent as ReactWheelEvent } from 'react'

export const MAX_ZOOM = 4
export const DOUBLE_TAP_ZOOM = 2.5
const SWIPE_PX = 50
const FLICK_PX = 20
const FLICK_MS = 250
const DISMISS_PX = 120
const DOUBLE_TAP_MS = 300
const TAP_SLOP_PX = 10
export const SLIDE_MS = 220

export interface Zoom {
  scale: number
  x: number
  y: number
  /** Ease into this zoom (double-tap, snap back) instead of following the fingers. */
  animate?: boolean
}

const IDENTITY: Zoom = { scale: 1, x: 0, y: 0 }

interface Point {
  x: number
  y: number
}

type Gesture =
  | { mode: 'pending' | 'swipe' | 'dismiss'; start: Point; t: number }
  | { mode: 'pan'; start: Point; from: Zoom }
  | { mode: 'pinch'; dist: number; mid: Point; from: Zoom }

interface Options {
  stageRef: RefObject<HTMLElement | null>
  imageRef: RefObject<HTMLElement | null>
  /** Changes when the shown photo changes: zoom resets. */
  photoKey: string | undefined
  canPrev: boolean
  canNext: boolean
  onPrev: () => void
  onNext: () => void
  onClose: () => void
}

const distance = (a: Point, b: Point) => Math.hypot(a.x - b.x, a.y - b.y)
const midpoint = (a: Point, b: Point) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 })

/**
 * Phone-gallery gestures for the photo viewer: pinch / double-tap / wheel to zoom, drag to pan a zoomed photo,
 * swipe sideways to the neighbouring photo (it follows the finger), swipe down to close.
 * Zoom is a CSS transform around the stage centre: screen = centre + (x, y) + scale * local.
 */
export function useViewerGestures({ stageRef, imageRef, photoKey, canPrev, canNext, onPrev, onNext, onClose }: Options) {
  // Zoom belongs to one photo: a stale entry for another photo reads as no zoom.
  const [zoomState, setZoomState] = useState<{ key: string | undefined; z: Zoom }>({ key: photoKey, z: IDENTITY })
  const zoom = zoomState.key === photoKey ? zoomState.z : IDENTITY
  const [slide, setSlide] = useState<{ x: number; y: number; animate: boolean }>({ x: 0, y: 0, animate: false })
  const pointers = useRef(new Map<number, Point>())
  const gesture = useRef<Gesture | null>(null)
  const lastTap = useRef<{ at: number; p: Point } | null>(null)
  // Handlers read the latest zoom between renders (pinch moves arrive faster than React re-renders).
  const zoomRef = useRef(zoomState)
  const currentZoom = () => (zoomRef.current.key === photoKey ? zoomRef.current.z : IDENTITY)
  const setZoom = (z: Zoom, animate = false) => {
    zoomRef.current = { key: photoKey, z: { ...z, animate } }
    setZoomState(zoomRef.current)
  }
  const settling = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => () => {
    if (settling.current) clearTimeout(settling.current)
  }, [])

  // iOS Safari: stop the page itself from zooming while pinching the photo.
  useEffect(() => {
    const el = stageRef.current
    if (!el) return
    const block = (e: Event) => e.preventDefault()
    el.addEventListener('gesturestart', block)
    return () => el.removeEventListener('gesturestart', block)
  }, [stageRef])

  /** Point relative to the stage centre. */
  const local = useCallback((p: Point): Point => {
    const r = stageRef.current?.getBoundingClientRect()
    return r ? { x: p.x - (r.left + r.width / 2), y: p.y - (r.top + r.height / 2) } : p
  }, [stageRef])

  /** Keeps a zoomed photo covering the stage: no panning past its edges. */
  const clamp = useCallback((z: Zoom): Zoom => {
    const scale = Math.min(MAX_ZOOM, Math.max(1, z.scale))
    if (scale === 1) return IDENTITY
    const stage = stageRef.current
    const img = imageRef.current
    const maxX = Math.max(0, ((img?.offsetWidth ?? 0) * scale - (stage?.clientWidth ?? 0)) / 2)
    const maxY = Math.max(0, ((img?.offsetHeight ?? 0) * scale - (stage?.clientHeight ?? 0)) / 2)
    return { scale, x: Math.min(maxX, Math.max(-maxX, z.x)), y: Math.min(maxY, Math.max(-maxY, z.y)) }
  }, [stageRef, imageRef])

  /** Zoom to `scale` keeping the photo point under `focus` (stage-centre coordinates) where it is. */
  const zoomAround = useCallback((from: Zoom, scale: number, focusFrom: Point, focusTo: Point = focusFrom) => {
    const s = Math.min(MAX_ZOOM, Math.max(1, scale))
    const px = (focusFrom.x - from.x) / from.scale
    const py = (focusFrom.y - from.y) / from.scale
    return clamp({ scale: s, x: focusTo.x - px * s, y: focusTo.y - py * s })
  }, [clamp])

  const settle = (x: number, after?: () => void) => {
    setSlide({ x, y: 0, animate: true })
    if (settling.current) clearTimeout(settling.current)
    settling.current = setTimeout(() => {
      settling.current = null
      after?.()
      setSlide({ x: 0, y: 0, animate: false })
    }, SLIDE_MS)
  }

  const onPointerDown = (e: ReactPointerEvent) => {
    if ((e.target as Element).closest('button') || settling.current) return
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // not supported (tests, old browsers): moves still arrive while over the stage
    }
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const pts = [...pointers.current.values()]
    if (pts.length === 1) {
      gesture.current = { mode: 'pending', start: pts[0], t: e.timeStamp }
    } else if (pts.length === 2) {
      setSlide({ x: 0, y: 0, animate: true })
      gesture.current = { mode: 'pinch', dist: distance(pts[0], pts[1]) || 1, mid: local(midpoint(pts[0], pts[1])), from: currentZoom() }
    }
  }

  const onPointerMove = (e: ReactPointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const g = gesture.current
    if (!g) return
    const pts = [...pointers.current.values()]

    if (g.mode === 'pinch') {
      if (pts.length < 2) return
      const mid = local(midpoint(pts[0], pts[1]))
      setZoom(zoomAround(g.from, g.from.scale * (distance(pts[0], pts[1]) / g.dist), g.mid, mid))
      return
    }
    const dx = e.clientX - g.start.x
    const dy = e.clientY - g.start.y
    if (g.mode === 'pending') {
      if (Math.hypot(dx, dy) < TAP_SLOP_PX) return
      if (currentZoom().scale > 1) gesture.current = { mode: 'pan', start: g.start, from: currentZoom() }
      else if (Math.abs(dx) > Math.abs(dy)) gesture.current = { ...g, mode: 'swipe' }
      else if (dy > 0) gesture.current = { ...g, mode: 'dismiss' }
      else gesture.current = null
      return onPointerMove(e)
    }
    if (g.mode === 'pan') {
      setZoom(clamp({ ...g.from, x: g.from.x + dx, y: g.from.y + dy }))
    } else if (g.mode === 'swipe') {
      const blocked = (dx > 0 && !canPrev) || (dx < 0 && !canNext)
      setSlide({ x: blocked ? dx / 3 : dx, y: 0, animate: false })
    } else if (g.mode === 'dismiss') {
      setSlide({ x: 0, y: Math.max(0, dy), animate: false })
    }
  }

  const onPointerUp = (e: ReactPointerEvent) => {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.delete(e.pointerId)
    const g = gesture.current
    if (!g) return
    const remaining = [...pointers.current.values()]

    if (g.mode === 'pinch') {
      // One finger lifted: keep panning with the other.
      gesture.current = remaining.length === 1 ? { mode: 'pan', start: remaining[0], from: currentZoom() } : null
      if (remaining.length === 0 && currentZoom().scale < 1.05) setZoom(IDENTITY, true)
      return
    }
    if (remaining.length > 0) return
    gesture.current = null
    const dx = e.clientX - g.start.x
    const dy = e.clientY - g.start.y

    if (g.mode === 'pending') {
      const tap = { at: e.timeStamp, p: { x: e.clientX, y: e.clientY } }
      const prev = lastTap.current
      if (prev && tap.at - prev.at < DOUBLE_TAP_MS && distance(prev.p, tap.p) < 30) {
        lastTap.current = null
        const z = currentZoom()
        setZoom(z.scale > 1 ? IDENTITY : zoomAround(z, DOUBLE_TAP_ZOOM, local(tap.p)), true)
      } else {
        lastTap.current = tap
      }
    } else if (g.mode === 'swipe') {
      const width = stageRef.current?.clientWidth ?? 0
      const flick = Math.abs(dx) > FLICK_PX && e.timeStamp - g.t < FLICK_MS
      if ((dx < -SWIPE_PX || (flick && dx < 0)) && canNext) settle(-width, onNext)
      else if ((dx > SWIPE_PX || (flick && dx > 0)) && canPrev) settle(width, onPrev)
      else settle(0)
    } else if (g.mode === 'dismiss') {
      if (dy > DISMISS_PX) onClose()
      else settle(0)
    }
  }

  const onPointerCancel = (e: ReactPointerEvent) => {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size === 0) {
      gesture.current = null
      if (slide.x !== 0 || slide.y !== 0) settle(0)
    }
  }

  const onWheel = (e: ReactWheelEvent) => {
    const z = currentZoom()
    setZoom(zoomAround(z, z.scale * Math.exp(-e.deltaY * 0.002), local({ x: e.clientX, y: e.clientY })))
  }

  return {
    zoom,
    slide,
    handlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel, onWheel },
  }
}
