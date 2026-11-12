import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { adminApi, ApiError } from '../lib/api'
import LoginPage from './LoginPage'

vi.mock('../lib/api', async (orig) => ({ ...(await orig<typeof import('../lib/api')>()), adminApi: { login: vi.fn(), forgotPassword: vi.fn() } }))

const renderPage = () => render(<MemoryRouter><LoginPage /></MemoryRouter>)

describe('Sign in', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(adminApi.login).mockReset()
  })

  it('shows a message under each empty or wrong field and does not call the server', async () => {
    const user = userEvent.setup()
    renderPage()
    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(screen.getByText('Enter a valid email address, like name@studio.com')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(adminApi.login).not.toHaveBeenCalled()
  })

  it('shows the server error in a banner', async () => {
    const user = userEvent.setup()
    vi.mocked(adminApi.login).mockRejectedValue(new ApiError(401, 'INVALID_CREDENTIALS', 'Wrong email or password'))
    renderPage()
    await user.type(screen.getByLabelText('Email'), 'owner@studio.com')
    await user.type(screen.getByLabelText('Password'), 'secret-pass')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Wrong email or password')).toBeInTheDocument()
  })
})
