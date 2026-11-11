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
    // iPhone: the share sheet's "Save Image" puts it in Photos. Where that can't open (photo not fetched in
    // time, older iOS), the file downloads straight to Files instead; never the press-and-hold hint.
    const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean }
    let blob = prefetched ?? null
    try {
      blob ??= await fetchPhoto(url)
    } catch {
      return 'hint'
    }
    const file = new File([blob], name, { type: 'image/jpeg' })
    if (nav.share && nav.canShare?.({ files: [file] })) {
      try {
        await nav.share({ files: [file] })
        return 'save-sheet'
      } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') return 'cancelled'
      }
    }
    try {
      saveBlob(blob, name)
      return 'saved'
    } catch {
      return 'hint'
    }
  }

  try {
    saveBlob(prefetched ?? (await fetchPhoto(url)), name)
    return 'saved'
  } catch {
    return 'hint'
  }
}

export type ShareResult = 'shared' | 'cancelled' | 'unsupported'

/** The caption that goes with a shared photo: credits the studio (and McreatiK), never links to the gallery. */
export function shareCaption(brand: { name: string; instagram: string | null; poweredByMcreatik: boolean }): string {
  const studio = brand.instagram ? `📸 ${brand.name} · instagram.com/${brand.instagram}` : `📸 ${brand.name}`
  return brand.poweredByMcreatik ? `${studio}\nLive photos by McreatiK` : studio
}

/**
 * Shares the photo itself (WhatsApp, Instagram, …) with the studio's credit as the caption. The gallery link is
 * never shared, so the gallery stays private to the people who have its QR code. Where the phone can't share
 * files, the caller saves the photo instead and copies the caption.
 */
export async function sharePhoto(opts: { url: string; eventSlug: string; photoId: string; caption: string; prefetched?: Blob | null }): Promise<ShareResult> {
  const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean }
  if (IS_DEMO || !nav.share || !nav.canShare) return 'unsupported'
  try {
    const blob = opts.prefetched ?? (await fetchPhoto(opts.url))
    const file = new File([blob], fileNameFor(opts.eventSlug, opts.photoId), { type: 'image/jpeg' })
    if (!nav.canShare({ files: [file] })) return 'unsupported'
    await nav.share({ files: [file], text: opts.caption })
    return 'shared'
  } catch (e) {
    return e instanceof DOMException && e.name === 'AbortError' ? 'cancelled' : 'unsupported'
  }
}
