import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'
import styles from './Button.module.css'

type Variant = 'primary' | 'secondary' | 'text'
type Size = 'md' | 'lg'

interface StyleProps {
  variant?: Variant
  size?: Size
  /** Ends the button with an arrow in a circle. It is decoration; the name stays the text. */
  arrow?: boolean
}

function classes({ variant = 'secondary', size = 'md', arrow = false }: StyleProps, extra?: string): string {
  return [styles.button, styles[variant], styles[size], arrow && styles.withArrow, extra].filter(Boolean).join(' ')
}

function Arrow() {
  return (
    <span className={styles.arrow} aria-hidden="true">
      <svg width="16" height="16" viewBox="0 0 16 16">
        <path d="M5 11L11 5M6 5h5v5" />
      </svg>
    </span>
  )
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, StyleProps {
  /** Disables the button and tells assistive technology that it is busy. */
  busy?: boolean
  children: ReactNode
}

export function Button({ variant, size, arrow, busy = false, disabled, className, type = 'button', children, ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={classes({ variant, size, arrow }, className)}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    >
      {arrow ? <span>{children}</span> : children}
      {arrow && <Arrow />}
    </button>
  )
}

export function ButtonLink({ variant, size, arrow, className, children, ...rest }: LinkProps & StyleProps) {
  return (
    <Link className={classes({ variant, size, arrow }, className as string | undefined)} {...rest}>
      {arrow ? <span>{children as ReactNode}</span> : children}
      {arrow && <Arrow />}
    </Link>
  )
}
