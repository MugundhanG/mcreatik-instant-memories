import { render, screen } from '@testing-library/react'
import EnvBadge from './EnvBadge'

describe('EnvBadge', () => {
  it('shows the label in upper case styling when set', () => {
    render(<EnvBadge label="beta" />)
    expect(screen.getByTestId('env-badge')).toHaveTextContent('beta')
  })

  it('renders nothing when the label is unset or blank', () => {
    const { container, rerender } = render(<EnvBadge label={undefined} />)
    expect(container).toBeEmptyDOMElement()
    rerender(<EnvBadge label="  " />)
    expect(container).toBeEmptyDOMElement()
  })

  it('can sit in a fixed corner for the gallery', () => {
    render(<EnvBadge label="beta" corner />)
    expect(screen.getByTestId('env-badge').parentElement).toHaveClass('fixed')
  })
})
