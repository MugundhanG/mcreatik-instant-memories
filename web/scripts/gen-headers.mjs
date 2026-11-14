// Run after `vite build`: writes dist/_headers with this environment's API and media origins in the CSP.
import fs from 'node:fs'
import { renderHeaders } from './headers.mjs'

fs.mkdirSync('dist', { recursive: true })
fs.writeFileSync('dist/_headers', renderHeaders(process.env))
console.log(`dist/_headers written (API ${process.env.VITE_API_BASE_URL || 'prod default'}, media ${process.env.VITE_MEDIA_BASE_URL || 'prod default'})`)
