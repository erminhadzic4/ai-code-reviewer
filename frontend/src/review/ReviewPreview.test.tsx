import { fireEvent, render, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ReviewPreview } from './ReviewPreview'

const NAMES = ['Score card', 'Finding card', 'Suggested fix', 'Issue count']

function layer(name: string) {
  return within(screen.getByRole('group', { name: 'Example report' })).getByRole('button', { name })
}

describe('ReviewPreview', () => {
  it('starts with no layer in focus', () => {
    render(<ReviewPreview />)

    for (const name of NAMES) {
      expect(layer(name)).toHaveAttribute('aria-pressed', 'false')
    }
    expect(screen.getByText('Click a layer to bring it forward.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Show all' })).not.toBeInTheDocument()
  })

  it('brings a clicked layer forward and moves the others back', () => {
    render(<ReviewPreview />)

    fireEvent.click(layer('Finding card'))

    expect(layer('Finding card')).toHaveAttribute('aria-pressed', 'true')
    expect(layer('Finding card')).toHaveAttribute('data-state', 'focused')
    expect(layer('Score card')).toHaveAttribute('aria-pressed', 'false')
    expect(layer('Score card')).toHaveAttribute('data-state', 'behind')
    expect(screen.getByText('Finding card in focus. Click another layer to switch.')).toBeInTheDocument()
  })

  it('switches to another layer and lets go on a second click', () => {
    render(<ReviewPreview />)

    fireEvent.click(layer('Finding card'))
    fireEvent.click(layer('Suggested fix'))
    expect(layer('Suggested fix')).toHaveAttribute('aria-pressed', 'true')
    expect(layer('Finding card')).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(layer('Suggested fix'))
    expect(layer('Suggested fix')).toHaveAttribute('aria-pressed', 'false')
    expect(layer('Suggested fix')).toHaveAttribute('data-state', 'rest')
  })

  it('shows all layers again with the button or Escape', () => {
    render(<ReviewPreview />)

    fireEvent.click(layer('Score card'))
    fireEvent.click(screen.getByRole('button', { name: 'Show all' }))
    expect(layer('Score card')).toHaveAttribute('aria-pressed', 'false')

    fireEvent.click(layer('Issue count'))
    fireEvent.keyDown(layer('Issue count'), { key: 'Escape' })
    expect(layer('Issue count')).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText('Click a layer to bring it forward.')).toBeInTheDocument()
  })

  it('shows the example with its scores and finding', () => {
    render(<ReviewPreview />)

    expect(screen.getByText('82')).toBeInTheDocument()
    expect(screen.getByText('Solid, with things to fix. Fix the 1 critical issue first.')).toBeInTheDocument()
    expect(screen.getByText('SQL query built from user input')).toBeInTheDocument()
  })

  it('ignores pointer moves over an element without a size', () => {
    render(<ReviewPreview />)
    const group = screen.getByRole('group', { name: 'Example report' })

    expect(() => {
      fireEvent.pointerMove(group.firstElementChild!, { clientX: 10, clientY: 10 })
      fireEvent.pointerLeave(group.firstElementChild!)
    }).not.toThrow()
  })
})
