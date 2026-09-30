import { saveBlob } from '../shared/saveBlob'

function fileNameFor(eventSlug: string, photoId: string) {
  return `${eventSlug}-${photoId.slice(0, 8)}.jpg`
}

async function fetchPhoto(url: string): Promise<Blob> {
  const response = await fetch(url, { mode: 'cors' })
  if (!response.ok) throw new Error('Download failed')
  return response.blob()
}

/** Saves the web-quality photo. Falls back to opening it (long-press to save) if the fetch is blocked. */
export async function downloadPhoto(url: string, eventSlug: string, photoId: string) {
  try {
    saveBlob(await fetchPhoto(url), fileNameFor(eventSlug, photoId))
  } catch {
    window.open(url, '_blank', 'noopener')
  }
}

export type ShareResult = 'shared-file' | 'shared-link' | 'copied' | 'cancelled' | 'unsupported'

/**
 * Mobile: shares the actual image (WhatsApp, Instagram, "Save Image" on iOS).
 * Otherwise shares/copies a link that opens this photo in the gallery.
 */
export async function sharePhoto(opts: { url: string; eventSlug: string; eventName: string; photoId: string }): Promise<ShareResult> {
  const link = `${window.location.origin}/e/${opts.eventSlug}?photo=${opts.photoId}`
  const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean }
  try {
    if (nav.share && nav.canShare) {
      const blob = await fetchPhoto(opts.url)
      const file = new File([blob], fileNameFor(opts.eventSlug, opts.photoId), { type: 'image/jpeg' })
      if (nav.canShare({ files: [file] })) {
        await nav.share({ files: [file], title: opts.eventName })
        return 'shared-file'
      }
    }
    if (nav.share) {
      await nav.share({ title: opts.eventName, url: link })
      return 'shared-link'
    }
    if (nav.clipboard) {
      await nav.clipboard.writeText(link)
      return 'copied'
    }
    return 'unsupported'
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') return 'cancelled'
    if (nav.clipboard) {
      await nav.clipboard.writeText(link)
      return 'copied'
    }
    return 'unsupported'
  }
}
