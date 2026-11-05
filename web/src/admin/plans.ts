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
export function unitPrice(item: PurchaseItem, billing: BillingSummary, plan?: Plan): number {
  switch (item) {
    case 'MAIN_EVENT': return billing.unitPrice
    case 'QUICK_EVENT': return billing.prices.quick
    case 'UPGRADE': return billing.prices.upgrade
    case 'PLAN': return packPrice(plan ?? billing.plan, billing.prices)
    case 'STORAGE': case 'EXTEND': return billing.prices.storageGb
    case 'CLIENT_SETUP': return billing.prices.clientSetup
  }
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
    case 'STORAGE': return `${n} GB of storage`
    case 'CLIENT_SETUP': return `${n} client galler${n === 1 ? 'y' : 'ies'}`
    case 'EXTEND': return `6 more months (${n} GB)`
  }
}
