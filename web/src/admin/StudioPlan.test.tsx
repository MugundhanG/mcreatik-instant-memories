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

  it('changes nothing until Save, then saves the package and the counts in one go', async () => {
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
    expect(screen.getByTestId('pack-events-s1')).toHaveTextContent('4 events a month · 1 used')
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    const quick = screen.getByRole('group', { name: 'Quick' })
    expect(within(quick).getByRole('button', { name: 'Take back one: Quick' })).toBeDisabled() // never below 0

    await user.click(screen.getByRole('button', { name: 'Give one: Main' }))
    await user.click(screen.getByRole('button', { name: 'Give one: Main' }))
    await user.click(screen.getByRole('button', { name: 'Take back one: Client' }))
    expect(screen.getByTestId('available-s1')).toHaveTextContent('Available: 7 events (3 left in pack this month) + 0 client')
    expect(screen.getByText('Not saved yet')).toBeInTheDocument()
    expect(bodies).toEqual([]) // nothing sent yet

    // Picking another package fills in its events per month (a new pack starts with its whole month).
    await user.selectOptions(screen.getByRole('combobox', { name: 'Plan' }), 'QUICK_2')
    expect(screen.getByTestId('pack-events-s1')).toHaveTextContent('2 events a month · 0 used')
    expect(screen.getByTestId('available-s1')).toHaveTextContent('Available: 6 events (2 left in pack this month)')

    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(onChange).toHaveBeenCalledTimes(1))
    expect(bodies).toEqual([{ plan: 'QUICK_2', addCredits: 2, addClientCredits: -1 }])
  })

  it('Cancel puts everything back', async () => {
    const user = userEvent.setup()
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<StudioPlan billing={studio} onChange={vi.fn()} />)
    await user.click(screen.getByRole('button', { name: 'Give one: Quick' }))
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByTestId('available-s1')).toHaveTextContent('Available: 5 events')
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })
})
