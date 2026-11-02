import type { ZipDownloadStatus } from './zipDownload'

const count = (n: number) => n.toLocaleString('en-IN')

function headline(s: ZipDownloadStatus) {
  switch (s.state) {
    case 'PREPARING': return 'Preparing your download…'
    case 'WAITING': return 'Starting download…'
    case 'QUEUED': return 'Waiting for other downloads to finish…'
    case 'RUNNING': return `Downloading ${s.percent}%`
    case 'DONE': return 'Download complete ✓'
    case 'FAILED': return 'Download stopped'
  }
}

function detail(s: ZipDownloadStatus) {
  switch (s.state) {
    case 'PREPARING': return 'Collecting your photos'
    case 'WAITING': return `${count(s.total)} ${s.total === 1 ? 'photo' : 'photos'} · if your phone asks, tap Download`
    case 'QUEUED': return 'It starts by itself in a moment. Keep this page open.'
    case 'RUNNING': return `${count(s.done)} of ${count(s.total)} photos · keep this page open`
    case 'DONE': return `${count(s.total)} ${s.total === 1 ? 'photo' : 'photos'} saved in your Downloads (on iPhone: Files app)`
    case 'FAILED': return s.error ?? 'The download was cancelled or the connection dropped. Please try again.'
  }
}

/** Floating card with the ZIP download's percentage, for clients and studios alike. */
export function ZipProgressCard({ status, onClose }: { status: ZipDownloadStatus | null; onClose: () => void }) {
  if (!status) return null
  const done = status.state === 'DONE'
  const failed = status.state === 'FAILED'
  const indeterminate = status.state === 'PREPARING' || status.state === 'WAITING' || status.state === 'QUEUED'
  const width = done ? 100 : status.percent
  return (
    <div
      role="status"
      aria-live="polite"
      data-testid="zip-progress"
      className="anim-sheet-up fixed bottom-4 left-1/2 z-[80] w-[min(26rem,calc(100%-2rem))] -translate-x-1/2 rounded-2xl bg-[#1c1917] p-4 text-white shadow-2xl"
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-semibold tabular-nums">{headline(status)}</p>
          {status.fileName && <p className="truncate text-xs text-white/60">{status.fileName}</p>}
        </div>
        {(done || failed) && (
          <button type="button" onClick={onClose} aria-label="Close" className="-mt-1 -mr-1 rounded-full px-2 py-1 text-white/70 hover:bg-white/10 hover:text-white">✕</button>
        )}
      </div>
      {!failed && (
        <div className="mt-3 h-2 overflow-hidden rounded-full bg-white/15" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={indeterminate ? undefined : width}>
          {indeterminate
            ? <div className="h-full w-1/3 animate-pulse rounded-full bg-white/50" />
            : <div className={`h-full rounded-full transition-[width] duration-700 ${done ? 'bg-emerald-400' : 'bg-[#e0b25c]'}`} style={{ width: `${width}%` }} />}
        </div>
      )}
      <p className={`mt-2 text-xs ${failed ? 'text-red-200' : 'text-white/70'} tabular-nums`}>{detail(status)}</p>
    </div>
  )
}
