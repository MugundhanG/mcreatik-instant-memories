import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { StudioBilling } from '../lib/types'
import { StudioPlan } from './BillingAdmin'

const studio: StudioBilling = {
  studioId: 's1', plan: 'MAIN_4', monthlyQuota: 4, usedThisMonth: 1, credits: 2, mainCredits: 2, quickCredits: 0,
  planPaidUntil: '2026-12-01', packActive: true, clientCredits: 1,
}

describe('Studio plan row (super admin)', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('shows the events a studio has available and gives or takes back one at a time', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    vi.stubGlobal('fetch', vi.fn(async (_url: RequestInfo | URL, init?: RequestInit) => {
      bodies.push(JSON.parse(String(init?.body)))
      return new Response(JSON.stringify(studio), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const onChange = vi.fn()
    render(<StudioPlan billing={studio} onChange={onChange} />)

    // 2 Main bought + 0 Quick + 3 left in this month's pack.
    expect(screen.getByTestId('available-s1')).toHaveTextContent('Available: 5 events (3 left in pack this month) + 1 client')
    const quick = screen.getByRole('group', { name: 'Quick' })
    expect(within(quick).getByRole('button', { name: 'Take back one: Quick' })).toBeDisabled() // never below 0

    await user.click(screen.getByRole('button', { name: 'Give one: Main' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    await user.click(screen.getByRole('button', { name: 'Take back one: Client' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(2))
    expect(bodies).toEqual([{ addCredits: 1 }, { addClientCredits: -1 }])
  })
})
