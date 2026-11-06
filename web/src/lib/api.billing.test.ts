import { describe, expect, it } from 'vitest'
import { withBillingDefaults } from './api'
import type { BillingSummary } from './types'

describe('Billing from an older backend', () => {
  it('fills in prices and typed credits so the dashboard keeps working until the backend is deployed', () => {
    const old = { plan: 'PER_EVENT', free: false, monthlyQuota: 0, usedThisMonth: 0, credits: 2, unitPrice: 2500, monthlyPrice: 5000,
      upiId: 'x@upi', payeeName: null, payments: [], onlinePayments: false } as unknown as BillingSummary
    const b = withBillingDefaults(old)
    expect(b.mainCredits).toBe(2)
    expect(b.quickCredits).toBe(0)
    expect(b.prices.main).toBe(2500)
    expect(b.prices.quick).toBe(2500)
  })

  it('leaves a current backend’s values alone', () => {
    const prices = { main: 2499, quick: 1499, main2: 3499, main4: 4999, quick2: 2499, quick4: 3999, upgrade: 1000, upgradeGuests: 300,
      storageGb: 15, freeStorageGb: 5, clientSetup: 250, activeMonths: 3, main2Extra: 1399, main4Extra: 899, quick2Extra: 899, quick4Extra: 699 }
    const b = withBillingDefaults({ credits: 3, mainCredits: 1, quickCredits: 2, unitPrice: 899, quickUnitPrice: 899, prices } as unknown as BillingSummary)
    expect(b.prices).toEqual(prices)
    expect(b.quickCredits).toBe(2)
    expect(b.quickUnitPrice).toBe(899)
  })

  it('prices an extra Quick event at the list price when an older backend sends no quickUnitPrice', () => {
    const b = withBillingDefaults({ credits: 0, unitPrice: 2499, prices: { main: 2499, quick: 1499 } } as unknown as BillingSummary)
    expect(b.quickUnitPrice).toBe(1499)
    expect(b.prices.main4Extra).toBe(999)
  })
})
