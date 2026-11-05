import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { PasswordInput } from './PasswordInput'

describe('PasswordInput', () => {
  it('hides the password until the eye is pressed, and hides it again', async () => {
    const user = userEvent.setup()
    render(<label>Password<PasswordInput defaultValue="secret-123" /></label>)
    const input = screen.getByLabelText('Password', { selector: 'input' })
    expect(input).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveValue('secret-123')
    await user.click(screen.getByRole('button', { name: 'Hide password' }))
    expect(input).toHaveAttribute('type', 'password')
  })
})
