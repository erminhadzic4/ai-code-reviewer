import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it } from 'vitest'
import { Button, ButtonLink } from './Button'

describe('Button', () => {
  it('keeps its text as the name when it shows an arrow', () => {
    render(<Button arrow>Review repository</Button>)

    const button = screen.getByRole('button', { name: 'Review repository' })
    const arrow = button.querySelector('[aria-hidden="true"]')
    expect(arrow).not.toBeNull()
    expect(arrow).toContainElement(button.querySelector('svg'))
  })

  it('is disabled and marked busy while busy', () => {
    render(
      <Button arrow busy>
        Submitting…
      </Button>,
    )

    const button = screen.getByRole('button', { name: 'Submitting…' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
  })

  it('renders a link with the same arrow', () => {
    render(
      <MemoryRouter>
        <ButtonLink to="/reviews" arrow>
          Your reviews
        </ButtonLink>
      </MemoryRouter>,
    )

    const link = screen.getByRole('link', { name: 'Your reviews' })
    expect(link).toHaveAttribute('href', '/reviews')
    expect(link.querySelector('[aria-hidden="true"] svg')).not.toBeNull()
  })
})
