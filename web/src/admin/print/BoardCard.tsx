import { useEffect, useMemo, useRef, useState } from 'react'
import { adminApi, ApiError } from '../../lib/api'
import type { Brand } from '../../lib/types'
import { BOARD, boardSvg, downloadBlob, renderJpeg, type BoardTheme } from '../../shared/printDesign'
import { Button, Card, ErrorText } from '../ui'
import { DesignPreview, ThemePicker } from './printParts'
import { themeOf, usePrintAssets } from './usePrintAssets'

/**
 * The studio's reusable board (Account page): pick a design, see it, download the 18 × 24 in print file.
 * The board is printed once; each event only needs a new 8 × 12 in card in its frame.
 */
export function BoardCard({ brand, saveTheme, onSaved }: {
  brand: Brand | undefined
  saveTheme: (t: BoardTheme) => Promise<Brand>
  onSaved: (b: Brand) => void
}) {
  const [theme, setTheme] = useState<BoardTheme>(themeOf(brand))
  const [saving, setSaving] = useState(false)
  const [making, setMaking] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const assets = usePrintAssets(brand, adminApi.accountLogo)
  const anchor = useRef<HTMLDivElement>(null)

  // Links to Account → Studio board (#board) land on this card.
  useEffect(() => {
    if (window.location.hash === '#board') anchor.current?.scrollIntoView({ block: 'start' })
  }, [])

  const svg = useMemo(
    () => (assets.fonts ? boardSvg(theme, assets.boardBrand, { fonts: assets.fonts }) : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- boardBrand is rebuilt each render from these values
    [theme, assets.fonts, assets.boardBrand.name, assets.boardBrand.logo, assets.boardBrand.logoScale, assets.boardBrand.instagram],
  )

  const pick = async (t: BoardTheme) => {
    const before = theme
    setTheme(t)
    setError(null)
    setSaving(true)
    try {
      onSaved(await saveTheme(t))
    } catch (e) {
      setTheme(before)
      setError(e instanceof ApiError ? e.message : 'Could not save the design')
    } finally {
      setSaving(false)
    }
  }

  const download = async () => {
    if (!svg) return
    setMaking(true)
    setError(null)
    try {
      downloadBlob(await renderJpeg(svg, BOARD.width, BOARD.height), `studio-board-${theme}-18x24in.jpg`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not make the print file')
    } finally {
      setMaking(false)
    }
  }

  return (
    <Card title="Studio board" className="scroll-mt-4">
      <div id="board" ref={anchor}>
        <p className="mb-4 text-sm text-muted">
          Print this board once and reuse it at every event. For each event, print a small event card (8 × 12 in) from
          the event's QR code and put it in the frame. Your event and table cards use the same design.
        </p>
        <ThemePicker value={theme} onChange={pick} disabled={saving || making} />
        <div className="mx-auto mt-4 max-w-64">
          <DesignPreview svg={svg} label={`Studio board, ${theme} design`} />
        </div>
        {assets.logoMissing && (
          <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">Your logo couldn't be loaded, so the board shows your studio name only. Try again later.</p>
        )}
        {assets.error && <p className="mt-3 text-xs text-red-700">The preview could not be prepared. Reload the page to try again.</p>}
        <ErrorText>{error}</ErrorText>
        <div className="mt-4 flex flex-col gap-2">
          <Button variant="primary" onClick={download} disabled={!svg || making}>
            {making ? 'Making print file…' : 'Download board (18 × 24 in)'}
          </Button>
          <p className="text-xs text-muted">
            JPG at 300 dpi, 18.25 × 24.25 in: 0.125 in extra on every side for trimming. Ask the print shop to print at
            18 × 24 in and trim to size (foam board or sunboard works well).
          </p>
        </div>
      </div>
    </Card>
  )
}
