import type { BillingSummary, Plan, Prices, Purchase, PurchaseItem } from '../lib/types'

export const PLAN_LABEL: Record<Plan, string> = {
  PER_EVENT: 'Per event',
  MAIN_2: 'Main pack · 2 events/month',
  MAIN_4: 'Main pack · 4 events/month',
  QUICK_2: 'Quick pack · 2 events/month',
  QUICK_4: 'Quick pack · 4 events/month',
  MONTHLY: 'Monthly package (older)',
  UNLIMITED: 'Unlimited',
}

export const PACKS: Plan[] = ['MAIN_2', 'MAIN_4', 'QUICK_2', 'QUICK_4']

export const isPack = (p: Plan) => PACKS.includes(p)

export const rupees = (n: number) => `₹${n.toLocaleString('en-IN')}`

/** A pack's monthly price. */
export function packPrice(plan: Plan, prices: Prices): number {
  switch (plan) {
    case 'MAIN_2': return prices.main2
    case 'MAIN_4': return prices.main4
    case 'QUICK_2': return prices.quick2
    case 'QUICK_4': return prices.quick4
    default: return 0
  }
}

/** What one unit of a purchase costs, the same way the server prices it. */
export function unitPrice(item: PurchaseItem, billing: BillingSummary, plan?: Plan, months?: number): number {
  switch (item) {
    case 'MAIN_EVENT': return billing.unitPrice
    case 'QUICK_EVENT': return billing.prices.quick
    case 'UPGRADE': return billing.prices.upgrade
    case 'PLAN': return packPrice(plan ?? billing.plan, billing.prices)
    case 'STORAGE': return billing.prices.storageGb * (months ?? billing.prices.activeMonths)
    case 'EXTEND': return billing.prices.storageGb * billing.prices.activeMonths
    case 'CLIENT_SETUP': return billing.prices.clientSetup
  }
}

/**
 * Months storage bought today is charged for: what is left of the gallery's open period, whole months rounded up,
 * at least 1 (no end date: the full period). The same rule as the server.
 */
export function monthsLeft(activeUntil: string | null | undefined, activeMonths: number, now = new Date()): number {
  if (!activeUntil) return Math.max(1, activeMonths)
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const until = new Date(`${activeUntil}T00:00:00`)
  if (until <= today) return 1
  let months = (until.getFullYear() - today.getFullYear()) * 12 + until.getMonth() - today.getMonth()
  const plus = (m: number) => new Date(today.getFullYear(), today.getMonth() + m, today.getDate())
  if (plus(months) > until) months -= 1
  return plus(months) < until ? months + 1 : Math.max(1, months)
}

/** About how many full-quality photos fit in this many GB (~12 MB each, with web versions). */
export const photosPerGb = (gb: number) => Math.round((gb * 1024) / 12.5 / 10) * 10

export const formatGb = (bytes: number) => `${(bytes / 1024 ** 3).toFixed(bytes < 10 * 1024 ** 3 ? 1 : 0)} GB`

/** "2 Main events", "Upgrade to Main", "1 month of Main pack · 4 events/month". */
export function describe(p: Purchase, billing: BillingSummary): string {
  const n = p.quantity ?? 1
  switch (p.item) {
    case 'MAIN_EVENT': return `${n} Main event${n === 1 ? '' : 's'}`
    case 'QUICK_EVENT': return `${n} Quick event${n === 1 ? '' : 's'}`
    case 'UPGRADE': return 'Upgrade to a Main event'
    case 'PLAN': return `${n} month${n === 1 ? '' : 's'} of ${PLAN_LABEL[p.plan ?? billing.plan]}`
    case 'STORAGE': { const m = p.months ?? billing.prices.activeMonths; return `${n} GB of storage for ${m} month${m === 1 ? '' : 's'}` }
    case 'CLIENT_SETUP': return `${n} client galler${n === 1 ? 'y' : 'ies'}`
    case 'EXTEND': return `${billing.prices.activeMonths} more months (${n} GB)`
  }
}
