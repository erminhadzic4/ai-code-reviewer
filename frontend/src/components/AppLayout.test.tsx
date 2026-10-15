import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { AuthProvider } from '../auth/AuthProvider'
import { AppLayout } from './AppLayout'

describe('AppLayout', () => {
  it('starts with a skip link to the main content, which can take focus', () => {
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<AppLayout />}>
              <Route index element={<p>Page</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main')
    const main = screen.getByRole('main')
    expect(main).toHaveAttribute('id', 'main')
    expect(main).toHaveAttribute('tabindex', '-1')
    expect(main).toHaveTextContent('Page')
  })
})
