/** True only in the demo build (npm run build:demo). Constant-folded, so demo code is dropped from production. */
export const IS_DEMO = import.meta.env.VITE_DEMO === '1'
