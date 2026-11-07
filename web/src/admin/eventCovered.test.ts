import { describe, expect, it } from 'vitest'
import type { BillingSummary } from '../lib/types'
import { eventCovered } from './plans'

const base = {
  plan: 'MAIN_4', free: false, monthlyQuota: 4, usedThisMonth: 1, credits: 0, unitPrice: 999, quickUnitPrice: 999, monthlyPrice: 4999,
  mainCredits: 0, quickCredits: 0, carryOver: 0, packActive: true, clientCredits: 0,
} as unknown as BillingSummary

describe('eventCovered (New event shows "Included" instead of a price)', () => {
  it('is included while the pack has events left, and priced once they are used', () => {
    expect(eventCovered(base, 'MAIN')).toBe(true)
    expect(eventCovered(base, 'QUICK')).toBe(true) // a Main pack covers Quick
    expect(eventCovered({ ...base, usedThisMonth: 4 }, 'MAIN')).toBe(false)
    expect(eventCovered({ ...base, usedThisMonth: 4, carryOver: 1 }, 'MAIN')).toBe(true)
  })

  it('a Quick pack covers Quick only; bought events and client credits count; a lapsed pack does not', () => {
    const quick = { ...base, plan: 'QUICK_2', monthlyQuota: 2 } as BillingSummary
    expect(eventCovered(quick, 'QUICK')).toBe(true)
    expect(eventCovered(quick, 'MAIN')).toBe(false)
    expect(eventCovered({ ...base, packActive: false }, 'MAIN')).toBe(false)
    expect(eventCovered({ ...base, packActive: false, mainCredits: 1 }, 'QUICK')).toBe(true)
    expect(eventCovered({ ...base, plan: 'PER_EVENT', packActive: false, quickCredits: 1 } as BillingSummary, 'MAIN')).toBe(false)
    expect(eventCovered(base, 'CLIENT')).toBe(false)
    expect(eventCovered({ ...base, clientCredits: 1 }, 'CLIENT')).toBe(true)
  })
})
