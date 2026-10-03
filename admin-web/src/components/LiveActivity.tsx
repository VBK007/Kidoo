import type { LiveScreen } from '../api'
import { since } from '../format'
import { Card } from './Tiles'

/**
 * Each account's most recent screen — what they're looking at, not just that
 * they're signed in. One row per account; a stale row (older than the server's
 * live window) simply stops appearing rather than being marked offline.
 */
export function LiveActivity({ screens }: { screens: LiveScreen[] }) {
  return (
    <Card title="Live activity" note="most recent screen per account, last 10 minutes">
      {screens.length === 0 ? (
        <p className="empty">Nobody's on the app right now.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Account</th>
              <th scope="col">Screen</th>
              <th scope="col">Watching</th>
              <th scope="col">Platform</th>
              <th scope="col">When</th>
            </tr>
          </thead>
          <tbody>
            {screens.map((row) => (
              <tr key={row.userId}>
                <td>{row.accountLabel}</td>
                <td>{row.screen}</td>
                <td>{row.itemTitle ?? '—'}</td>
                <td>{row.platform ?? '—'}</td>
                <td>{since(row.at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  )
}
