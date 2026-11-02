import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { ForgotPassword } from './ForgotPassword'

describe('Forgot password', () => {
  it('sends the email and gives the same answer whether or not the account exists', async () => {
    const user = userEvent.setup()
    const request = vi.fn().mockResolvedValue(undefined)
    const onBack = vi.fn()
    render(<ForgotPassword request={request} onBack={onBack} initialEmail="owner@lenslight.in " />)
    await user.click(screen.getByRole('button', { name: 'Send temporary password' }))
    expect(request).toHaveBeenCalledWith('owner@lenslight.in')
    expect(await screen.findByTestId('forgot-sent')).toHaveTextContent('If an account exists for owner@lenslight.in')
    await user.click(screen.getByRole('button', { name: 'Back to sign in' }))
    expect(onBack).toHaveBeenCalled()
  })
})
