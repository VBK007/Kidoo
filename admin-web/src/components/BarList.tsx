export type Bar = {
  name: string
  value: number
  /** The hover line — what this row is, spelled out. */
  detail?: string
}

/**
 * Horizontal bars for comparing magnitude.
 *
 * One hue, because every list here answers "which is biggest", never "which is
 * which" — categorical colour would spend eight hues to say what position on a
 * shared baseline already says, and would bury the row that matters.
 *
 * Bars run against the largest row rather than a rounded axis maximum: with a
 * direct label on every row there is no axis to read, so the widths only have
 * to be honest relative to each other. Rows at zero keep their place with a
 * plain track, so a category that is empty reads as empty rather than missing.
 */
export function BarList({ bars, format }: { bars: Bar[]; format: (value: number) => string }) {
  if (bars.length === 0) {
    return <p className="empty">Nothing here yet.</p>
  }
  const peak = Math.max(...bars.map((bar) => bar.value), 0)

  return (
    <div className="bars">
      {bars.map((bar) => {
        const share = peak > 0 ? (bar.value / peak) * 100 : 0
        return (
          <div
            className={bar.value === 0 ? 'bar-row zero' : 'bar-row'}
            key={bar.name}
            title={bar.detail ?? `${bar.name}: ${format(bar.value)}`}
          >
            <span className="name">{bar.name}</span>
            <span className="track">
              {bar.value > 0 ? <span className="fill" style={{ width: `${share}%` }} /> : null}
            </span>
            <span className="amount">{format(bar.value)}</span>
          </div>
        )
      })}
    </div>
  )
}
