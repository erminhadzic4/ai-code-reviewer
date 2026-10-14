import { startTransition } from 'react'
import { NavLink, useNavigate } from 'react-router'
import { useAuth } from '../auth/AuthContext'
import { Button, ButtonLink } from './Button'
import styles from './AppHeader.module.css'
import { Logo } from './Logo'

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
      <div className={styles.inner}>
        <Logo />
        {state.status !== 'loading' && (
          <nav aria-label="Main" className={styles.nav}>
            {state.status === 'signedIn' ? (
              <>
                <NavLink to="/reviews" end className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}>
                  Reviews
                </NavLink>
                <ButtonLink to="/" variant="secondary">
                  New review
                </ButtonLink>
                <Button variant="text" onClick={logOut}>
                  Log out
                </Button>
              </>
            ) : (
              <>
                <NavLink to="/login" className={({ isActive }) => (isActive ? styles.linkActive : styles.link)}>
                  Log in
                </NavLink>
                <ButtonLink to="/register" variant="secondary">
                  Sign up
                </ButtonLink>
              </>
            )}
          </nav>
        )}
      </div>
    </header>
  )
}
