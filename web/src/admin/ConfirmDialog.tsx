import { useEffect, useState } from 'react'
import { Button, inputClass } from './ui'

export interface ConfirmOptions {
  title: string
  body: string
  confirmLabel: string
  danger?: boolean
  /** When set, the button stays disabled until this exact text is typed (for irreversible deletes). */
  requireText?: string
}

export function ConfirmDialog({ options, onClose }: { options: ConfirmOptions; onClose: (ok: boolean) => void }) {
  const [typed, setTyped] = useState('')
  const ready = !options.requireText || typed === options.requireText

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose(false)
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" onClick={() => onClose(false)}>
      <div
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <h3 id="confirm-title" className="text-lg font-semibold">{options.title}</h3>
        <p className="mt-2 text-sm text-muted">{options.body}</p>
        {options.requireText && (
          <label className="mt-4 block text-sm">
            Type <span className="font-semibold">{options.requireText}</span> to confirm
            <input id="confirm-text" autoFocus className={`${inputClass} mt-1.5`} value={typed} onChange={(e) => setTyped(e.target.value)} />
          </label>
        )}
        <div className="mt-6 flex justify-end gap-2">
          <Button onClick={() => onClose(false)} autoFocus={!options.requireText}>Cancel</Button>
          <Button variant={options.danger ? 'danger' : 'primary'} disabled={!ready} onClick={() => onClose(true)}>
            {options.confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}
