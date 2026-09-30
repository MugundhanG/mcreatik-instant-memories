import { useCallback, useState } from 'react'
import { ConfirmDialog, type ConfirmOptions } from './ConfirmDialog'

/** In-page replacement for window.confirm(): `const [confirm, dialog] = useConfirm(); if (await confirm({...}))`. */
export function useConfirm() {
  const [request, setRequest] = useState<{ options: ConfirmOptions; resolve: (ok: boolean) => void } | null>(null)
  const confirm = useCallback(
    (options: ConfirmOptions) => new Promise<boolean>((resolve) => setRequest({ options, resolve })),
    [],
  )
  const onClose = useCallback(
    (ok: boolean) => {
      request?.resolve(ok)
      setRequest(null)
    },
    [request],
  )
  const dialog = request ? <ConfirmDialog options={request.options} onClose={onClose} /> : null
  return [confirm, dialog] as const
}
