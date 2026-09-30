import { describe, expect, it } from 'vitest'
import { liveEvent, photo } from '../test/mocks'
import { reducer, type LiveGalleryState } from './useLiveGallery'

const base: LiveGalleryState = {
  event: liveEvent,
  photos: [photo(2), photo(1)],
  incoming: [],
  nextCursor: null,
  status: 'ready',
  connection: 'live',
  loadingMore: false,
}

describe('live gallery reducer', () => {
  it('merges out-of-order arrivals newest first and counts them', () => {
    const next = reducer(base, { type: 'arrived', photos: [photo(4), photo(3)], show: true })
    expect(next.photos.map((p) => p.cursor)).toEqual(['cursor-4', 'cursor-3', 'cursor-2', 'cursor-1'])
    expect(next.event?.photoCount).toBe(5)
  })

  it('never duplicates a photo that is already shown or waiting', () => {
    const held = reducer(base, { type: 'arrived', photos: [photo(3)], show: false })
    const again = reducer(held, { type: 'arrived', photos: [photo(3), photo(2)], show: false })
    expect(again).toBe(held)
    expect(held.incoming).toHaveLength(1)
  })

  it('merges waiting photos when revealed', () => {
    const held = reducer(base, { type: 'arrived', photos: [photo(3)], show: false })
    expect(reducer(held, { type: 'showIncoming' }).photos.map((p) => p.cursor)).toEqual(['cursor-3', 'cursor-2', 'cursor-1'])
  })

  it('appends older pages without duplicates', () => {
    const next = reducer(base, { type: 'moreLoaded', page: { photos: [photo(1), photo(0)], nextCursor: null } })
    expect(next.photos.map((p) => p.cursor)).toEqual(['cursor-2', 'cursor-1', 'cursor-0'])
  })
})
