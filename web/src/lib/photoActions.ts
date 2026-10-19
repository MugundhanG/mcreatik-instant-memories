import { IS_DEMO } from '../demo/isDemo'
import { saveBlob } from '../shared/saveBlob'

function fileNameFor(eventSlug: string, photoId: string) {
  return `${eventSlug}-${photoId.slice(0, 8)}.jpg`
}

/**
 * Fetches the web-quality photo as a blob. `no-store` matters: the viewer already showed this image through a plain
 * <img> (no CORS), and reusing that cached response for a CORS fetch fails, because storage only adds the CORS
 * headers when the request carries an Origin.
 */
export async function fetchPhoto(url: string): Promise<Blob> {
  const response = await fetch(url, { mode: 'cors', cache: 'no-store' })
  if (!response.ok) throw new Error('Download failed')
  return response.blob()
}

function userAgent() {
  return typeof navigator === 'undefined' ? '' : navigator.userAgent
}

/** iPhone/iPad (iPadOS reports itself as a Mac with touch). Files saved by a website go to Files, not Photos. */
export function isIOS() {
  return /iP(hone|ad|od)/.test(userAgent()) || (/Macintosh/.test(userAgent()) && navigator.maxTouchPoints > 1)
}

/** In-app browsers (Instagram, Facebook) silently ignore downloads. */
export function isInAppBrowser() {
  return /Instagram|FBAN|FBAV|FB_IAB|Line\//.test(userAgent())
}

/**
 * - `saved`: written to the device (Android: Downloads, shown in Gallery/Google Photos; desktop: Downloads)
 * - `save-sheet`: iPhone "Save Image" sheet was shown (the only way a website can save into Photos)
 * - `cancelled`: the user closed that sheet
 * - `hint`: the browser cannot save files; tell the guest to press and hold the photo instead
 */
export type DownloadResult = 'saved' | 'save-sheet' | 'cancelled' | 'hint' | 'unavailable'

/**
 * Saves the web-quality photo without ever navigating away from the gallery. On iPhone it needs the photo already
 * fetched ({@code prefetched}): Safari only opens the save sheet straight from a tap, not after a network wait.
 */
export async function downloadPhoto(url: string, eventSlug: string, photoId: string,
                                    prefetched?: Blob | null): Promise<DownloadResult> {
  if (IS_DEMO) return 'unavailable' // the hosted demo frame blocks downloads
  if (isInAppBrowser()) return 'hint'
  const name = fileNameFor(eventSlug, photoId)

  if (isIOS()) {
    const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean }
    if (!prefetched || !nav.share || !nav.canShare) return 'hint'
    const file = new File([prefetched], name, { type: 'image/jpeg' })
    if (!nav.canShare({ files: [file] })) return 'hint'
    try {
      await nav.share({ files: [file] })
      return 'save-sheet'
    } catch (e) {
      return e instanceof DOMException && e.name === 'AbortError' ? 'cancelled' : 'hint'
    }
  }

  try {
    saveBlob(prefetched ?? (await fetchPhoto(url)), name)
    return 'saved'
  } catch {
    return 'hint'
  }
}

export type ShareResult = 'shared-file' | 'shared-link' | 'copied' | 'cancelled' | 'unsupported'

/**
 * Mobile: shares the actual image (WhatsApp, Instagram, "Save Image" on iOS).
 * Otherwise shares/copies a link that opens this photo in the gallery.
 * Not shown in the gallery for now (SHOW_SHARE in PhotoViewer).
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
