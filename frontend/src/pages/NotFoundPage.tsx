import { ButtonLink } from '../components/Button'
import { Eyebrow } from '../components/Eyebrow'
import styles from './NotFoundPage.module.css'

export function NotFoundPage() {
  return (
    <div className={`container ${styles.page}`}>
      <Eyebrow>404</Eyebrow>
      <h1 className={styles.title}>Page not found</h1>
      <p className={styles.text}>Nothing lives at this address.</p>
      <ButtonLink to="/" variant="primary" size="lg" arrow>
        Go to the start page
      </ButtonLink>
    </div>
  )
}
