/**
 * "Remember me" for Find my photos: the random match code the server gave this guest, kept in this browser only, per
 * gallery; the server holds the face matches behind the code. Until a first photo is found there is no code yet, so
 * the guest's own selfie (small, on this phone only, never on our server) is kept to look again as photos arrive.
 */
const key = (slug: string) => `mcreatik.facematch.${slug}`

export function loadMatchId(slug: string): string | null {
  try {
    const id = localStorage.getItem(key(slug))
    return id && id.length > 0 ? id : null
  } catch {
    return null
  }
}

export function saveMatchId(slug: string, matchId: string) {
  try {
    localStorage.setItem(key(slug), matchId)
  } catch {
    // storage blocked: the results still show for this visit
  }
}

export function clearMatchId(slug: string) {
  try {
    localStorage.removeItem(key(slug))
    localStorage.removeItem(checkKey(slug))
  } catch {
    // nothing to clear
  }
}

const selfieKey = (slug: string) => `mcreatik.faceselfie.${slug}`
const checkKey = (slug: string) => `mcreatik.facecheck.${slug}`

/** The selfie kept on this phone while nothing has matched yet (a JPEG data URL), or null. */
export function loadSelfie(slug: string): Blob | null {
  try {
    const url = localStorage.getItem(selfieKey(slug))
    if (!url || !url.startsWith('data:image/jpeg;base64,')) return null
    const bytes = Uint8Array.from(atob(url.slice(23)), (c) => c.charCodeAt(0))
    return new Blob([bytes], { type: 'image/jpeg' })
  } catch {
    return null
  }
}

export async function saveSelfie(slug: string, selfie: Blob): Promise<void> {
  try {
    const url = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () => resolve(String(reader.result))
      reader.onerror = () => reject(reader.error)
      reader.readAsDataURL(selfie)
    })
    if (url.startsWith('data:image/jpeg;base64,')) localStorage.setItem(selfieKey(slug), url)
  } catch {
    // storage full or blocked: the guest can still search again by hand
  }
}

export function clearSelfie(slug: string) {
  try {
    localStorage.removeItem(selfieKey(slug))
  } catch {
    // nothing to clear
  }
}

/** When this phone last looked for new photos of the guest on its own (ms), so automatic checks stay cheap. */
export function lastCheck(slug: string): number {
  try {
    return Number(localStorage.getItem(checkKey(slug))) || 0
  } catch {
    return 0
  }
}

export function markChecked(slug: string, at = Date.now()) {
  try {
    localStorage.setItem(checkKey(slug), String(at))
  } catch {
    // fine: checks just run a little more often
  }
}
