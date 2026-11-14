// Renders Cloudflare's dist/_headers from scripts/headers.template for one environment.
// The CSP must name the API and media origins of that environment; production values are the defaults.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

export const PROD_API_ORIGIN = 'https://im-api.mcreatik.com'
export const PROD_MEDIA_ORIGIN = 'https://im-media.mcreatik.com'
const templatePath = path.join(path.dirname(fileURLToPath(import.meta.url)), 'headers.template')

/** "https://api.beta.mcreatik.com/" -> "https://api.beta.mcreatik.com". Throws on anything that is not http(s). */
export function toOrigin(value, name) {
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${name} is not a valid URL: "${value}"`)
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') throw new Error(`${name} must be an http(s) URL: "${value}"`)
  return url.origin
}

export function renderHeaders(env = {}) {
  const api = toOrigin(env.VITE_API_BASE_URL || PROD_API_ORIGIN, 'VITE_API_BASE_URL')
  const media = toOrigin(env.VITE_MEDIA_BASE_URL || PROD_MEDIA_ORIGIN, 'VITE_MEDIA_BASE_URL')
  return fs.readFileSync(templatePath, 'utf8').replaceAll('{{API_ORIGIN}}', api).replaceAll('{{MEDIA_ORIGIN}}', media)
}
