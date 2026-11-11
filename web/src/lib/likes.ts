/** The photos this guest has liked in an event, remembered in this browser only. */
const key = (slug: string) => `mcreatik.likes.${slug}`

export function loadLikes(slug: string): Set<string> {
  try {
    const raw = localStorage.getItem(key(slug))
    const list: unknown = raw ? JSON.parse(raw) : []
    return new Set(Array.isArray(list) ? list.filter((x): x is string => typeof x === 'string') : [])
  } catch {
    return new Set()
  }
}

export function saveLikes(slug: string, likes: Set<string>) {
  try {
    localStorage.setItem(key(slug), JSON.stringify([...likes]))
  } catch {
    // storage blocked: the like still counts for this visit
  }
}
