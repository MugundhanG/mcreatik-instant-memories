import type { ReactNode } from 'react'

/** Shown to guests and the couple when McreatiK has turned this gallery off. */
export function GalleryUnavailable({ message, children }: { message: string; children?: ReactNode }) {
  return (
    <main className="flex min-h-dvh flex-col items-center justify-center bg-paper px-8 text-center" data-testid="gallery-unavailable">
      <p className="font-display text-3xl text-ink">This gallery is currently unavailable</p>
      <p className="mt-3 max-w-sm text-sm text-muted">{message.replace(/^This gallery is currently unavailable\.\s*/, '')}</p>
      {children}
    </main>
  )
}
