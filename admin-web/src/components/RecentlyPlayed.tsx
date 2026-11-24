import type { RecentPlay } from '../api'
import { since } from '../format'
import { Card } from './Tiles'

/**
 * How far a play got, in words: the finished flag wins over the bar, because a row
 * can read 100% without being marked done and the operator wants the truthful one.
 * A null percent is an honest gap — the file's duration was never known — not zero.
 */
function progress(row: RecentPlay): string {
  if (row.watched) {
    return 'Finished'
  }
  return row.percentComplete == null ? '—' : `${row.percentComplete}%`
}

/**
 * What each account has recently played, newest first — the audit feed.
 *
 * Distinct from the live-activity table above it: that is where accounts are *now*,
 * this is a durable history of what was actually watched, one row per distinct play
 * (the backend's grain is an upsert on profile+item, so a film that played for an hour
 * is one row here, not sixty). The profile is named as well as the account because
 * within one household the question is usually which of them watched it.
 */
export function RecentlyPlayed({ plays }: { plays: RecentPlay[] }) {
  return (
    <Card title="Recently played" note="latest title per profile, newest first">
      {plays.length === 0 ? (
        <p className="empty">Nothing has been played yet.</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">Account</th>
              <th scope="col">Profile</th>
              <th scope="col">Title</th>
              <th scope="col">Progress</th>
              <th scope="col">When</th>
            </tr>
          </thead>
          <tbody>
            {plays.map((row) => (
              <tr key={`${row.profileId}:${row.itemId}`}>
                <td>{row.accountLabel}</td>
                <td>{row.profileName ?? '—'}</td>
                <td>{row.itemTitle ?? '—'}</td>
                <td>{progress(row)}</td>
                <td>{since(row.at)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  )
}
