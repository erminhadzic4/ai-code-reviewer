import { Outlet } from 'react-router'
import { AppHeader } from './AppHeader'

export function AppLayout() {
  return (
    <>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      <AppHeader />
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  )
}
