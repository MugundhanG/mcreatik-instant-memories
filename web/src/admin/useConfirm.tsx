import { useCallback, useState } from 'react'
import { usePresence } from '../shared/usePresence'
import { ConfirmDialog, type ConfirmOptions } from './ConfirmDialog'

/** In-page replacement for window.confirm(): `const [confirm, dialog] = useConfirm(); if (await confirm({...}))`. */
export function useConfirm() {
  const [request, setRequest] = useState<{ options: ConfirmOptions; resolve: (ok: boolean) => void } | null>(null)
  // The last question stays on screen while the dialog fades out.
  const [last, setLast] = useState<ConfirmOptions | null>(null)
  const { mounted, closing } = usePresence(request !== null)
  const confirm = useCallback(
    (options: ConfirmOptions) => new Promise<boolean>((resolve) => {
      setLast(options)
      setRequest({ options, resolve })
    }),
    [],
  )
  const onClose = useCallback(
    (ok: boolean) => {
      request?.resolve(ok)
      setRequest(null)
    },
    [request],
  )
  const dialog = mounted && last ? <ConfirmDialog options={request?.options ?? last} onClose={onClose} closing={closing} /> : null
  return [confirm, dialog] as const
}
