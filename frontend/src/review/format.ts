const TIME = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' })
const DATE = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
const NUMBER = new Intl.NumberFormat()

export function shortSha(sha: string): string {
  return sha.slice(0, 7)
}

export function formatTime(iso: string): string {
  return TIME.format(new Date(iso))
}

export function formatNumber(value: number): string {
  return NUMBER.format(value)
}

/** "just now", "5 min ago", "3 h ago", then the date. */
export function formatAgo(iso: string, now: Date = new Date()): string {
  const minutes = Math.floor((now.getTime() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  return DATE.format(new Date(iso))
}

/** "1 file", "3 files". */
export function plural(count: number, singular: string, pluralForm = `${singular}s`): string {
  return `${formatNumber(count)} ${count === 1 ? singular : pluralForm}`
}
