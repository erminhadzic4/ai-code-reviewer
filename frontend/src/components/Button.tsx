import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link, type LinkProps } from 'react-router'
import styles from './Button.module.css'

type Variant = 'primary' | 'secondary' | 'text'
type Size = 'md' | 'lg'

interface StyleProps {
  variant?: Variant
  size?: Size
}

function classes({ variant = 'secondary', size = 'md' }: StyleProps, extra?: string): string {
  return [styles.button, styles[variant], styles[size], extra].filter(Boolean).join(' ')
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement>, StyleProps {
  /** Disables the button and tells assistive technology that it is busy. */
  busy?: boolean
  children: ReactNode
}

export function Button({ variant, size, busy = false, disabled, className, type = 'button', ...rest }: ButtonProps) {
  return (
    <button
      type={type}
      className={classes({ variant, size }, className)}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...rest}
    />
  )
}

export function ButtonLink({ variant, size, className, ...rest }: LinkProps & StyleProps) {
  return <Link className={classes({ variant, size }, className as string | undefined)} {...rest} />
}
