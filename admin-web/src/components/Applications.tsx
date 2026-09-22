import type { ApplicationStats } from '../api'
import { plain, since } from '../format'
import { Card } from './Tiles'

/**
 * Users per client app.
 *
 * A table, not a chart: six numbers per row all carry meaning, and past about
 * seven classes a chart is a worse table. The inline bar on the first column
 * is the only visual encoding, and it is a share of the busiest app, so the
 * eye gets the ranking without the numbers having to be re-read.
 *
 * The footnote is not decoration. Someone who uses the phone and the browser
 * is a row in both, so these columns do not sum to the account total, and a
 * dashboard that let a reader assume otherwise would be lying quietly.
 */
export function Applications({ apps }: { apps: ApplicationStats[] }) {
  const peak = Math.max(...apps.map((app) => app.users), 0)

  return (
    <Card title="By application" note="one row per app that has signed anyone in">
      {apps.length === 0 ? (
        <p className="empty">
          No sign-in has been logged yet. Apps appear here once they call
          <code> POST /api/analytics/login</code>.
        </p>
      ) : (
        <>
          <table>
            <thead>
              <tr>
                <th scope="col">Application</th>
                <th scope="col">Users</th>
                <th scope="col">Active 7d</th>
                <th scope="col">Active 30d</th>
                <th scope="col">Sign-ins 30d</th>
                <th scope="col">Last seen</th>
              </tr>
            </thead>
            <tbody>
              {apps.map((app) => (
                <tr key={app.platform}>
                  <td>
                    <span className="platform">
                      <span
                        className={app.platform === 'unknown' ? 'swatch unknown' : 'swatch'}
                        aria-hidden="true"
                      />
                      {app.platform}
                    </span>
                  </td>
                  <td>
                    {plain(app.users)}
                    <span
                      className="share"
                      style={{ width: `${peak > 0 ? (app.users / peak) * 100 : 0}%` }}
                      aria-hidden="true"
                    />
                  </td>
                  <td>{plain(app.activeLast7Days)}</td>
                  <td>{plain(app.activeLast30Days)}</td>
                  <td>{plain(app.loginsLast30Days)}</td>
                  <td>{since(app.lastSeenAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="meta" style={{ marginBottom: 0 }}>
            Someone who uses two apps counts in both rows, so these do not add up to the
            account total. <code>unknown</code> is a build that declares no platform.
          </p>
        </>
      )}
    </Card>
  )
}
