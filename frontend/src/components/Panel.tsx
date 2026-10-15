import type { HTMLAttributes, ReactNode } from 'react'
import styles from './Panel.module.css'

interface Props extends HTMLAttributes<HTMLElement> {
  as?: 'div' | 'section' | 'figure'
  padding?: 'none' | 'md' | 'lg'
  /** Classes for the inner panel, where the content and its layout live. */
  bodyClassName?: string
  children: ReactNode
}

/**
 * A card in two layers: a translucent frame and a dark panel inside it. Attributes such as
 * role and aria-labelledby go on the frame, the outer element.
 */
export function Panel({ as: Tag = 'div', padding = 'md', className, bodyClassName, children, ...rest }: Props) {
  return (
    <Tag className={[styles.frame, className].filter(Boolean).join(' ')} {...rest}>
      <div className={[styles.body, styles[padding], bodyClassName].filter(Boolean).join(' ')}>{children}</div>
    </Tag>
  )
}
