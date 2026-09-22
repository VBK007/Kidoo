import { useState, type FormEvent } from 'react'
import { ApiError, signIn, type Credentials } from '../api'

/**
 * Both halves of the gate in one form.
 *
 * The admin key is asked for here rather than baked into the build for the
 * reason the server refuses either half alone: a key shipped inside a bundle is
 * a key anyone who opens devtools now holds. It lives in `sessionStorage`, so
 * closing the tab ends it.
 */
export function SignIn({ onSignedIn }: { onSignedIn: (credentials: Credentials) => void }) {
  const [usernameOrEmail, setUsernameOrEmail] = useState('')
  const [password, setPassword] = useState('')
  const [adminKey, setAdminKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const { token, user } = await signIn(usernameOrEmail.trim(), password)
      if (user.role !== 'PARENT') {
        // Said here rather than left to the dashboard's 403, which cannot
        // explain itself: the server answers both failures identically on
        // purpose, so that a response never confirms a guessed key.
        setError('This console is owner-only. That account is not a PARENT.')
        return
      }
      onSignedIn({ token, adminKey: adminKey.trim(), account: user })
    } catch (failure) {
      setError(
        failure instanceof ApiError
          ? failure.message
          : 'Could not reach the server. Is it running on the API base this console points at?',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="signin">
      <form onSubmit={submit}>
        <p className="eyebrow">Kido</p>
        <h1>Admin console</h1>
        <p className="lede">
          Owner-only. Needs a PARENT account <em>and</em> the server&rsquo;s admin key —
          either alone is refused.
        </p>

        {error ? (
          <p className="banner" role="alert">
            <span className="icon" aria-hidden="true">
              !
            </span>
            <span>{error}</span>
          </p>
        ) : null}

        <div className="field">
          <label htmlFor="who">Username or email</label>
          <input
            id="who"
            value={usernameOrEmail}
            onChange={(event) => setUsernameOrEmail(event.target.value)}
            autoComplete="username"
            required
            autoFocus
          />
        </div>

        <div className="field">
          <label htmlFor="password">Password</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            required
          />
        </div>

        <div className="field">
          <label htmlFor="key">Admin key</label>
          <input
            id="key"
            type="password"
            value={adminKey}
            onChange={(event) => setAdminKey(event.target.value)}
            autoComplete="off"
            required
          />
          <p className="hint">
            The <code>X-Admin-Key</code> header — <code>ADMIN_API_KEY</code> on the server.
          </p>
        </div>

        <button className="btn primary" type="submit" disabled={busy}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </main>
  )
}
