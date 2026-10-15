import { startTransition } from 'react'
import { Link, NavLink, useNavigate } from 'react-router'
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
        {state.status !== 'loading' && (
          <nav aria-label="Main" className={styles.nav}>
            {state.status === 'signedIn' ? (
              <>
                <NavLink to="/reviews" end className={linkClass}>
                  Reviews
                </NavLink>
                <Link to="/" className={styles.pill}>
                  New review
                </Link>
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
