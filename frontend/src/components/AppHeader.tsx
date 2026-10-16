import { startTransition } from 'react'
import { NavLink, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import styles from './AppHeader.module.css'
import { Logo } from './Logo'

function linkClass({ isActive }: { isActive: boolean }): string {
  return isActive ? `${styles.link} ${styles.active}` : styles.link
}

function pillClass({ isActive }: { isActive: boolean }): string {
  return isActive ? `${styles.pill} ${styles.pillActive}` : styles.pill
}

export function AppHeader() {
  const { state, signOut } = useAuth()
  const navigate = useNavigate()

  // The router navigates in a transition. Signing out in the same one keeps a protected page
  // from seeing the signed-out state first and redirecting to the login page.
  function logOut() {
    startTransition(() => {
      navigate('/')
      signOut()
    })
  }

  return (
    <header className={styles.header}>
      <div className={styles.bar}>
        <Logo />
        {/* Hidden while it is not known whether anyone is signed in. */}
        {(state.status === 'signedIn' || state.status === 'signedOut') && (
          <nav aria-label="Main" className={styles.nav}>
            {state.status === 'signedIn' ? (
              <>
                <NavLink to="/reviews" end className={linkClass}>
                  Reviews
                </NavLink>
                <NavLink to="/reviews/new" className={pillClass}>
                  New review
                </NavLink>
                <button type="button" className={styles.link} onClick={logOut}>
                  Log out
                </button>
              </>
            ) : (
              <>
                <NavLink to="/login" className={linkClass}>
                  Log in
                </NavLink>
                <NavLink to="/register" className={pillClass}>
                  Sign up
                </NavLink>
              </>
            )}
          </nav>
        )}
      </div>
    </header>
  )
}
