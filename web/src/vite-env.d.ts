/// <reference types="vite/client" />
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_MEDIA_BASE_URL?: string
  /** Set to e.g. "beta" on non-production builds: shows a small badge in the admin header and the gallery. */
  readonly VITE_ENV_LABEL?: string
}
