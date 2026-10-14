import { useId, useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router'
import { login, register } from '../api/auth'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import type { LoginRedirectState } from '../auth/RequireAuth'
import { Button } from '../components/Button'
import { InlineError } from '../components/InlineError'
import styles from './AuthPage.module.css'

type Mode = 'login' | 'register'

const TEXT = {
  login: {
    title: 'Log in',
    submit: 'Log in',
    busy: 'Logging in…',
    switchText: 'No account yet?',
    switchLink: 'Sign up',
    switchTo: '/register',
  },
  register: {
    title: 'Create an account',
    submit: 'Create account',
    busy: 'Creating account…',
    switchText: 'Already have an account?',
    switchLink: 'Log in',
    switchTo: '/login',
  },
} as const

type Field = 'email' | 'password'
const FIELD_NAME: Record<Field, string> = { email: 'Email', password: 'Password' }

/** Login and registration: the same two fields, a different endpoint. */
export function AuthPage({ mode }: { mode: Mode }) {
  const { state, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const redirect = (location.state as LoginRedirectState | null) ?? {}
  const text = TEXT[mode]
  const id = useId()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<Field, string>>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (state.status === 'signedIn' && !busy) {
    return <Navigate to="/" replace />
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const missing: Partial<Record<Field, string>> = {}
    if (!email.trim()) missing.email = 'Email is required.'
    if (!password) missing.password = 'Password is required.'
    setFieldErrors(missing)
    setFormError(null)
    if (Object.keys(missing).length > 0) {
      return
    }

    setBusy(true)
    try {
      const response = await (mode === 'login' ? login : register)({ email, password })
      signIn(response)
      const back: LoginRedirectState | undefined = redirect.repoUrl ? { repoUrl: redirect.repoUrl } : undefined
      navigate(redirect.from ?? '/', { replace: true, state: back })
    } catch (e) {
      setBusy(false)
      if (!(e instanceof ApiError)) {
        setFormError('Something went wrong. Try again.')
        return
      }
      const fromServer: Partial<Record<Field, string>> = {}
      for (const field of ['email', 'password'] as const) {
        const message = e.fieldErrors[field]
        if (message) fromServer[field] = `${FIELD_NAME[field]} ${message}.`
      }
      setFieldErrors(fromServer)
      if (Object.keys(fromServer).length === 0) {
        setFormError(e.message)
      }
    }
  }

  return (
    <div className={`container ${styles.page}`}>
      <h1 className={styles.title}>{text.title}</h1>
      {redirect.repoUrl && <p className={styles.context}>Sign in to review {redirect.repoUrl}.</p>}
      <form className={styles.form} onSubmit={onSubmit} noValidate>
        {formError && <InlineError>{formError}</InlineError>}
        {(['email', 'password'] as const).map((field) => (
          <div key={field} className={styles.field}>
            <label htmlFor={`${id}-${field}`} className={styles.label}>
              {FIELD_NAME[field]}
            </label>
            <input
              id={`${id}-${field}`}
              className={styles.input}
              type={field === 'email' ? 'email' : 'password'}
              autoComplete={field === 'email' ? 'email' : mode === 'login' ? 'current-password' : 'new-password'}
              value={field === 'email' ? email : password}
              onChange={(event) => (field === 'email' ? setEmail : setPassword)(event.target.value)}
              aria-invalid={fieldErrors[field] ? true : undefined}
              aria-describedby={fieldErrors[field] ? `${id}-${field}-error` : undefined}
            />
            {mode === 'register' && field === 'password' && !fieldErrors.password && (
              <p className={styles.hint}>At least 8 characters.</p>
            )}
            {fieldErrors[field] && <InlineError id={`${id}-${field}-error`}>{fieldErrors[field]}</InlineError>}
          </div>
        ))}
        <Button type="submit" variant="primary" size="lg" busy={busy} className={styles.submit}>
          {busy ? text.busy : text.submit}
        </Button>
      </form>
      <p className={styles.switch}>
        {text.switchText}{' '}
        <Link to={text.switchTo} state={redirect} className={styles.switchLink}>
          {text.switchLink}
        </Link>
      </p>
    </div>
  )
}
