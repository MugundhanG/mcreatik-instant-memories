/**
 * "Remember me" for Find my photos: the random match code the server gave this guest, kept in this browser only, per
 * gallery. Nothing else is stored here (no photos, no selfie); the server holds the face matches behind the code.
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
  } catch {
    // nothing to clear
  }
}
