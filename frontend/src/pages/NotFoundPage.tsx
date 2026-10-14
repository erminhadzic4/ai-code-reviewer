import { ButtonLink } from '../components/Button'
import styles from './NotFoundPage.module.css'

export function NotFoundPage() {
  return (
    <div className={`container ${styles.page}`}>
      <p className={styles.code}>404</p>
      <h1 className={styles.title}>Page not found</h1>
      <p className={styles.text}>Nothing lives at this address.</p>
      <ButtonLink to="/" variant="secondary">
        Go to the start page
      </ButtonLink>
    </div>
  )
}
