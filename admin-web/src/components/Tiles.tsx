import type { ReactNode } from 'react'

/**
 * The single number the page leads with. Exactly one per view — a second hero
 * is two headlines, which is none.
 */
export function Hero({ value, caption }: { value: string; caption: string }) {
  return (
    <div className="hero">
      <span className="figure">{value}</span>
      <span className="caption">{caption}</span>
    </div>
  )
}

/**
 * One headline number with the line that qualifies it.
 *
 * A stat tile rather than a one-bar chart: there is nothing to compare a single
 * current value against, and a bar of one is a rectangle pretending otherwise.
 */
export function Tile({
  label,
  value,
  sub,
}: {
  label: string
  value: string
  sub?: ReactNode
}) {
  return (
    <div className="card tile">
      <div className="label">{label}</div>
      <div className="value">{value}</div>
      {sub ? <div className="sub">{sub}</div> : null}
    </div>
  )
}

export function Card({
  title,
  note,
  children,
}: {
  title: string
  note?: string
  children: ReactNode
}) {
  return (
    <section className="card">
      <header>
        <h2>{title}</h2>
        {note ? <span className="note">{note}</span> : null}
      </header>
      {children}
    </section>
  )
}
