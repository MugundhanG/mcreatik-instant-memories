import fs from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { renderHeaders, toOrigin } from './headers.mjs'

const prodFixture = fs.readFileSync(path.resolve(process.cwd(), 'src/test/fixtures/prod-headers.txt'), 'utf8')

describe('renderHeaders', () => {
  it('is byte-identical to the original production _headers when no variables are set', () => {
    expect(renderHeaders({})).toBe(prodFixture)
  })

  it('is byte-identical when the production values are given explicitly (with a trailing slash)', () => {
    expect(renderHeaders({ VITE_API_BASE_URL: 'https://im-api.mcreatik.com/', VITE_MEDIA_BASE_URL: 'https://im-media.mcreatik.com' })).toBe(prodFixture)
  })

  it('substitutes the beta API and media origins and drops the production ones', () => {
    const out = renderHeaders({ VITE_API_BASE_URL: 'https://api.beta.mcreatik.com', VITE_MEDIA_BASE_URL: 'https://pub-abc123.r2.dev/' })
    expect(out).toContain("img-src 'self' data: blob: https://pub-abc123.r2.dev;")
    expect(out).toContain("connect-src 'self' https://api.beta.mcreatik.com https://pub-abc123.r2.dev https://*.r2.cloudflarestorage.com;")
    expect(out).not.toContain('im-api.mcreatik.com')
    expect(out).not.toContain('im-media.mcreatik.com')
    expect(out).not.toContain('{{')
  })

  it('rejects values that are not URLs', () => {
    expect(() => renderHeaders({ VITE_API_BASE_URL: 'nope' })).toThrow(/VITE_API_BASE_URL/)
    expect(() => toOrigin('ftp://x.example', 'X')).toThrow(/http/)
  })
})
