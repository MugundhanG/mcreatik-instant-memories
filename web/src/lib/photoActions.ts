import { IS_DEMO } from '../demo/isDemo'
import { saveBlob } from '../shared/saveBlob'
import { API_BASE_URL } from './api'

function fileNameFor(eventSlug: string, photoId: string) {
  return `${eventSlug}-${photoId.slice(0, 8)}.jpg`
}

/**
 * The media URL with `dl=1`. The viewer already loaded this image through a plain <img> (no Origin header), and
 * Cloudflare's edge caches that response without CORS headers and does not vary its cache on Origin, so a later CORS
 * fetch of the same URL gets the cached copy and fails. A different query string is a different cache key, so
 * download fetches are cached (and answered with Access-Control-Allow-Origin) separately. Signed URLs are left
 * alone: an extra parameter would break their signature.
 */
export function withDownloadParam(url: string): string {
  if (/[?&](X-Amz-Signature|sig)=/i.test(url)) return url
  const hash = url.indexOf('#')
  const base = hash >= 0 ? url.slice(0, hash) : url
  const fragment = hash >= 0 ? url.slice(hash) : ''
  if (/[?&]dl=/.test(base)) return url
  return `${base}${base.includes('?') ? '&' : '?'}dl=1${fragment}`
}

/** The API's own download of a guest photo (attachment, no CORS needed for a plain link). */
export function guestDownloadUrl(eventSlug: string, photoId: string): string {
  return `${API_BASE_URL}/api/public/events/${encodeURIComponent(eventSlug)}/photos/${encodeURIComponent(photoId)}/download`
}

async function fetchBlob(url: string): Promise<Blob> {
  const response = await fetch(url, { mode: 'cors', cache: 'no-store' })
  if (!response.ok) throw new Error('Download failed')
  return response.blob()
}

/**
 * Fetches the web-quality photo as a blob: the media URL (with `dl=1`, see above) first; if that fails and the
 * caller has an API download URL, that one (the API allows CORS from the gallery).
 */
export async function fetchPhoto(url: string, apiUrl?: string): Promise<Blob> {
  try {
    return await fetchBlob(withDownloadParam(url))
  } catch (e) {
    if (!apiUrl) throw e
    return fetchBlob(apiUrl)
  }
}

/** Starts a top-level download of a URL that answers with Content-Disposition: attachment; the page stays put. */
function triggerDownload(url: string, name: string) {
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

function userAgent() {
  return typeof navigator === 'undefined' ? '' : navigator.userAgent
}

/** iPhone/iPad (iPadOS reports itself as a Mac with touch). Files saved by a website go to Files, not Photos. */
export function isIOS() {
  return /iP(hone|ad|od)/.test(userAgent()) || (/Macintosh/.test(userAgent()) && navigator.maxTouchPoints > 1)
}

export function isAndroid() {
  return /Android/.test(userAgent())
}

/** Android intent link that opens a page in Chrome (the way out of an in-app browser). */
export function chromeIntentUrl(href: string): string {
  const url = new URL(href)
  return `intent://${url.host}${url.pathname}${url.search}#Intent;scheme=${url.protocol.replace(':', '')};package=com.android.chrome;S.browser_fallback_url=${encodeURIComponent(href)};end`
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
 * - `in-app`: Instagram/Facebook browser: tell the guest to open the gallery in Chrome/Safari
 */
export type DownloadResult = 'saved' | 'save-sheet' | 'cancelled' | 'hint' | 'in-app' | 'unavailable'

/**
 * Saves the web-quality photo without ever navigating away from the gallery. Fallback chain: media URL, then the API
 * download as a blob, then (Android/desktop) a top-level download of the API link; only iPhone ends in the hint.
 * On iPhone it needs the photo already fetched ({@code prefetched}): Safari only opens the save sheet straight from
 * a tap, not after a network wait. {@code apiUrl} is the API download link (guest gallery only).
 */
export async function downloadPhoto(url: string, eventSlug: string, photoId: string,
                                    prefetched?: Blob | null, apiUrl?: string): Promise<DownloadResult> {
  if (IS_DEMO) return 'unavailable' // the hosted demo frame blocks downloads
  const name = fileNameFor(eventSlug, photoId)

  if (isInAppBrowser()) {
    // In-app browsers ignore scripted file saves. Some Android ones still honour a plain attachment link: try it,
    // and tell the guest how to get out to a real browser either way.
    if (apiUrl && isAndroid()) triggerDownload(apiUrl, name)
    return 'in-app'
  }

  if (isIOS()) {
    // iPhone: the share sheet's "Save Image" puts it in Photos. Where that can't open (photo not fetched in
    // time, older iOS), the file downloads straight to Files instead; the press-and-hold hint is the last resort.
    const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean }
    let blob = prefetched ?? null
    try {
      blob ??= await fetchPhoto(url, apiUrl)
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
    saveBlob(prefetched ?? (await fetchPhoto(url, apiUrl)), name)
    return 'saved'
  } catch {
    if (!apiUrl) return 'hint'
    // Neither fetch worked (CORS, flaky network): a plain attachment link downloads without any of that.
    triggerDownload(apiUrl, name)
    return 'saved'
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
export async function sharePhoto(opts: { url: string; eventSlug: string; photoId: string; caption: string; prefetched?: Blob | null; apiUrl?: string }): Promise<ShareResult> {
  const nav = navigator as Navigator & { canShare?: (data: ShareData) => boolean }
  if (IS_DEMO || !nav.share || !nav.canShare) return 'unsupported'
  try {
    const blob = opts.prefetched ?? (await fetchPhoto(opts.url, opts.apiUrl))
    const file = new File([blob], fileNameFor(opts.eventSlug, opts.photoId), { type: 'image/jpeg' })
    if (!nav.canShare({ files: [file] })) return 'unsupported'
    await nav.share({ files: [file], text: opts.caption })
    return 'shared'
  } catch (e) {
    return e instanceof DOMException && e.name === 'AbortError' ? 'cancelled' : 'unsupported'
  }
}
