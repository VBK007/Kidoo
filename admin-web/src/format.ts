/** Number, byte and time formatting, shared so two tiles never disagree. */

const COMPACT = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 })
const PLAIN = new Intl.NumberFormat('en')

/**
 * Stat-tile values: exact up to four digits, compact above.
 *
 * A dashboard's job is the order of magnitude, but "1,284 accounts" is a fact
 * an operator may need to quote, and 1.3K is not.
 */
export function compact(value: number): string {
  return value < 10_000 ? PLAIN.format(value) : COMPACT.format(value)
}

export const plain = (value: number): string => PLAIN.format(value)

/** Binary units, since this counts disk. */
export function bytes(value: number): string {
  if (value <= 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  const index = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)))
  const scaled = value / 1024 ** index
  return `${scaled.toFixed(index === 0 || scaled >= 100 ? 0 : 1)} ${units[index]}`
}

export function hours(value: number): string {
  if (value === 0) {
    return '0h'
  }
  if (value < 1) {
    return `${Math.round(value * 60)}m`
  }
  return `${value.toFixed(1)}h`
}

/**
 * "4 minutes ago" for a timestamp, plain date once it is older than a week.
 *
 * Relative time is what "last seen" is read for, but past a few days the exact
 * day is more use than "23 days ago".
 */
export function since(iso: string | null): string {
  if (!iso) {
    return 'never'
  }
  const then = new Date(iso)
  if (Number.isNaN(then.getTime())) {
    return 'unknown'
  }
  const seconds = (Date.now() - then.getTime()) / 1000
  if (seconds < 60) {
    return 'just now'
  }
  if (seconds < 3600) {
    return `${Math.floor(seconds / 60)}m ago`
  }
  if (seconds < 86_400) {
    return `${Math.floor(seconds / 3600)}h ago`
  }
  if (seconds < 604_800) {
    return `${Math.floor(seconds / 86_400)}d ago`
  }
  return then.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
}

export function clockTime(iso: string): string {
  const at = new Date(iso)
  return Number.isNaN(at.getTime())
    ? '—'
    : at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** Poster categories and component kinds arrive as enum names. */
export function titleCase(key: string): string {
  return key
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}
