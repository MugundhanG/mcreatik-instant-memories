import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { usePresence } from './usePresence'

function Box({ open }: { open: boolean }) {
  const { mounted, closing } = usePresence(open, 150)
  return mounted ? <div data-testid="box" data-closing={closing} /> : null
}

describe('usePresence', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('shows at once, and stays for its exit animation before going', () => {
    const { rerender } = render(<Box open={false} />)
    expect(screen.queryByTestId('box')).toBeNull()
    rerender(<Box open />)
    expect(screen.getByTestId('box')).toHaveAttribute('data-closing', 'false')
    rerender(<Box open={false} />)
    expect(screen.getByTestId('box')).toHaveAttribute('data-closing', 'true')
    act(() => vi.advanceTimersByTime(149))
    expect(screen.getByTestId('box')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(1))
    expect(screen.queryByTestId('box')).toBeNull()
  })

  it('reopening while closing cancels the exit', () => {
    const { rerender } = render(<Box open />)
    rerender(<Box open={false} />)
    rerender(<Box open />)
    act(() => vi.advanceTimersByTime(500))
    expect(screen.getByTestId('box')).toHaveAttribute('data-closing', 'false')
  })
})
