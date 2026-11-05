import { useMemo } from 'react'
import { BOARD_THEMES, svgDataUrl, THEMES, type BoardTheme } from '../../shared/printDesign'

/** Shows an SVG design as an image, at the width of its container. */
export function DesignPreview({ svg, label, className = '' }: { svg: string | null; label: string; className?: string }) {
  const url = useMemo(() => (svg ? svgDataUrl(svg) : null), [svg])
  return url
    ? <img src={url} alt={label} className={`block h-auto w-full shadow-[0_8px_24px_rgba(0,0,0,0.14)] ${className}`} data-testid="design-preview" />
    : <div className={`flex aspect-[3/4] w-full items-center justify-center rounded bg-stone-100 text-xs text-muted ${className}`}>Preparing preview…</div>
}

/** Cream, dark or white. */
export function ThemePicker({ value, onChange, disabled }: { value: BoardTheme; onChange: (t: BoardTheme) => void; disabled?: boolean }) {
  return (
    <div role="radiogroup" aria-label="Board design" className="flex gap-2">
      {BOARD_THEMES.map((t) => (
        <button key={t} type="button" role="radio" aria-checked={value === t} disabled={disabled} onClick={() => onChange(t)}
          className={`flex flex-1 items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium transition-colors disabled:opacity-60 ${
            value === t ? 'border-ink bg-stone-50 text-ink' : 'border-line text-stone-600 hover:border-stone-400'}`}>
          <span aria-hidden="true" className="size-4 shrink-0 rounded-full border border-[#c9a35a]" style={{ background: THEMES[t].swatch }} />
          {THEMES[t].label}
        </button>
      ))}
    </div>
  )
}
