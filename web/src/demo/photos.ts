/** Generated "event photos" for the demo: soft wedding-light scenes, drawn on a canvas. No external images. */

const PALETTES: [string, string, string][] = [
  ['#f6d5c3', '#b86b77', '#fff4e6'],
  ['#f3e3c3', '#8c6a4f', '#fff8e1'],
  ['#1f3b4d', '#e2b07a', '#ffe7b8'],
  ['#2c2a4a', '#d98f8f', '#ffd9c7'],
  ['#e9dcc9', '#5b6c5d', '#fffbe9'],
  ['#402c2c', '#f0c27b', '#fff0cc'],
  ['#123524', '#e8cfa0', '#fff3d6'],
  ['#3b1f2b', '#c9a0dc', '#fbe8ff'],
]

function mulberry32(seed: number) {
  return () => {
    seed |= 0
    seed = (seed + 0x6d2b79f5) | 0
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function draw(ctx: CanvasRenderingContext2D, w: number, h: number, seed: number, caption: string) {
  const rnd = mulberry32(seed)
  const [a, b, light] = PALETTES[Math.floor(rnd() * PALETTES.length)]
  const angle = rnd() * Math.PI
  const bg = ctx.createLinearGradient(w / 2 - Math.cos(angle) * w, h / 2 - Math.sin(angle) * h, w / 2 + Math.cos(angle) * w, h / 2 + Math.sin(angle) * h)
  bg.addColorStop(0, a)
  bg.addColorStop(1, b)
  ctx.fillStyle = bg
  ctx.fillRect(0, 0, w, h)

  // Warm glow band, like string lights behind a stage.
  const glowY = h * (0.3 + rnd() * 0.4)
  const glow = ctx.createLinearGradient(0, glowY - h * 0.25, 0, glowY + h * 0.25)
  glow.addColorStop(0, 'rgba(255,255,255,0)')
  glow.addColorStop(0.5, 'rgba(255,236,200,0.28)')
  glow.addColorStop(1, 'rgba(255,255,255,0)')
  ctx.fillStyle = glow
  ctx.fillRect(0, 0, w, h)

  // Bokeh
  const count = 26 + Math.floor(rnd() * 20)
  for (let i = 0; i < count; i++) {
    const x = rnd() * w
    const y = glowY + (rnd() - 0.5) * h * 0.9
    const r = (0.02 + rnd() * 0.09) * Math.max(w, h)
    const g = ctx.createRadialGradient(x, y, 0, x, y, r)
    const alpha = 0.08 + rnd() * 0.3
    g.addColorStop(0, light + Math.round(alpha * 255).toString(16).padStart(2, '0'))
    g.addColorStop(0.7, light + Math.round(alpha * 0.6 * 255).toString(16).padStart(2, '0'))
    g.addColorStop(1, light + '00')
    ctx.fillStyle = g
    ctx.beginPath()
    ctx.arc(x, y, r, 0, Math.PI * 2)
    ctx.fill()
  }

  // Vignette
  const v = ctx.createRadialGradient(w / 2, h / 2, Math.min(w, h) * 0.3, w / 2, h / 2, Math.max(w, h) * 0.75)
  v.addColorStop(0, 'rgba(0,0,0,0)')
  v.addColorStop(1, 'rgba(0,0,0,0.45)')
  ctx.fillStyle = v
  ctx.fillRect(0, 0, w, h)

  ctx.fillStyle = 'rgba(255,255,255,0.72)'
  ctx.font = `500 ${Math.round(Math.min(w, h) * 0.028)}px ui-sans-serif, system-ui, sans-serif`
  ctx.fillText(caption, Math.round(w * 0.035), Math.round(h - Math.min(w, h) * 0.04))
}

function render(longEdge: number, portrait: boolean, seed: number, caption: string, quality: number) {
  const w = portrait ? Math.round(longEdge * 2 / 3) : longEdge
  const h = portrait ? longEdge : Math.round(longEdge * 2 / 3)
  const canvas = document.createElement('canvas')
  canvas.width = w
  canvas.height = h
  draw(canvas.getContext('2d')!, w, h, seed, caption)
  return { url: canvas.toDataURL('image/jpeg', quality), width: w, height: h }
}

export interface DemoImage {
  thumbnailUrl: string
  webUrl: string
  width: number
  height: number
}

export function generatePhoto(seed: number, caption: string): DemoImage {
  const portrait = mulberry32(seed * 7 + 3)() < 0.3
  const web = render(1400, portrait, seed, caption, 0.8)
  const thumb = render(480, portrait, seed, caption, 0.75)
  return { thumbnailUrl: thumb.url, webUrl: web.url, width: web.width, height: web.height }
}
