import type { RecentSignup } from '../api'
import { since, titleCase } from '../format'
import { Card } from './Tiles'

/** The newest accounts, named — what a growth tile's "+1 today" can't say on its own. */
export function RecentSignups({ signups }: { signups: RecentSignup[] }) {
  return (
    <Card title="Recent signups" note="newest first">
      {signups.length === 0 ? (
        <p className="empty">No accounts yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Account</th>
              <th scope="col">Role</th>
              <th scope="col">Platform</th>
              <th scope="col">Joined</th>
            </tr>
          </thead>
          <tbody>
            {signups.map((account) => (
              <tr key={account.id}>
                <td>
                  <div>{account.displayName ?? account.username}</div>
                  <div className="meta">{account.email}</div>
                </td>
                <td>{titleCase(account.role)}</td>
                <td>{account.platform ?? '—'}</td>
                <td>{since(account.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  )
}
