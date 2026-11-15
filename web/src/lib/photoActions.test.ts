import { describe, expect, it } from 'vitest'
import { chromeIntentUrl, guestDownloadUrl, withDownloadParam } from './photoActions'

describe('withDownloadParam', () => {
  it('adds dl=1 to a plain media URL', () => {
    expect(withDownloadParam('https://im-media.mcreatik.com/web/a.jpg')).toBe('https://im-media.mcreatik.com/web/a.jpg?dl=1')
  })
  it('respects an existing query string and a fragment', () => {
    expect(withDownloadParam('https://m.test/a.jpg?v=2')).toBe('https://m.test/a.jpg?v=2&dl=1')
    expect(withDownloadParam('https://m.test/a.jpg?v=2#x')).toBe('https://m.test/a.jpg?v=2&dl=1#x')
  })
  it('does not add it twice, and leaves signed URLs alone', () => {
    expect(withDownloadParam('https://m.test/a.jpg?dl=1')).toBe('https://m.test/a.jpg?dl=1')
    const signed = 'https://x.r2.cloudflarestorage.com/a.jpg?X-Amz-Signature=abc'
    expect(withDownloadParam(signed)).toBe(signed)
  })
})

describe('urls', () => {
  it('builds the API download link', () => {
    expect(guestDownloadUrl('arun-7k', 'p1')).toMatch(/\/api\/public\/events\/arun-7k\/photos\/p1\/download$/)
  })
  it('builds a Chrome intent link for the current page', () => {
    expect(chromeIntentUrl('https://gallery.mcreatik.com/e/arun-7k?photo=p1')).toBe(
      'intent://gallery.mcreatik.com/e/arun-7k?photo=p1#Intent;scheme=https;package=com.android.chrome;S.browser_fallback_url=https%3A%2F%2Fgallery.mcreatik.com%2Fe%2Farun-7k%3Fphoto%3Dp1;end')
  })
})
