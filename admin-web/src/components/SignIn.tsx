import { useState, type FormEvent } from 'react'
import { ApiError, DEFAULT_BASE, normaliseBase, signIn, type Credentials } from '../api'

/**
 * The last server address, remembered across tabs.
 *
 * `localStorage`, deliberately, where the token and the admin key are in
 * `sessionStorage`: an address is not a secret, and it is the one thing here
 * worth surviving a closed tab. Retyping a tunnel hostname is the whole reason
 * this field exists, so remembering it is the point rather than a convenience.
 */
const SERVER_KEY = 'kido-admin-server'

function remembered(): string {
  try {
    return localStorage.getItem(SERVER_KEY) ?? DEFAULT_BASE
  } catch {
    // Private browsing, or storage the browser refuses. The field still works;
    // it just opens empty, which is the same as never having been here.
    return DEFAULT_BASE
  }
}

/**
 * Both halves of the gate in one form.
 *
 * The admin key is asked for here rather than baked into the build for the
 * reason the server refuses either half alone: a key shipped inside a bundle is
 * a key anyone who opens devtools now holds. It lives in `sessionStorage`, so
 * closing the tab ends it.
 */
export function SignIn({ onSignedIn }: { onSignedIn: (credentials: Credentials) => void }) {
  const [server, setServer] = useState(remembered)
  const [usernameOrEmail, setUsernameOrEmail] = useState('')
  const [password, setPassword] = useState('')
  const [adminKey, setAdminKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    setError(null)
    const baseUrl = normaliseBase(server)
    try {
      const { token, user } = await signIn(baseUrl, usernameOrEmail.trim(), password)
      if (user.role !== 'PARENT') {
        // Said here rather than left to the dashboard's 403, which cannot
        // explain itself: the server answers both failures identically on
        // purpose, so that a response never confirms a guessed key.
        setError('This console is owner-only. That account is not a PARENT.')
        return
      }
      // Remembered only now. An address that could not even be signed in
      // against is not the one to greet the next tab with.
      try {
        localStorage.setItem(SERVER_KEY, baseUrl)
      } catch {
        // Nothing to do and nothing worth saying: the session in front of us
        // works, and only the remembering failed.
      }
      // Written back so the field shows what was actually used — a typed
      // `kido.example` reached as `https://kido.example` should say so.
      setServer(baseUrl)
      onSignedIn({ token, adminKey: adminKey.trim(), account: user, baseUrl })
    } catch (failure) {
      setError(
        failure instanceof ApiError
          ? failure.message
          : 'Could not reach the server. Is it running on the address above?',
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
          <label htmlFor="server">Server</label>
          <input
            id="server"
            value={server}
            onChange={(event) => setServer(event.target.value)}
            placeholder="https://something.trycloudflare.com"
            autoComplete="url"
            spellCheck={false}
            autoFocus
          />
          <p className="hint">
            Where this server is now. A free tunnel gets a new hostname every time it
            restarts, so paste the current one here. Leave it empty to use whatever host
            is serving this page.
          </p>
        </div>

        <div className="field">
          <label htmlFor="who">Username or email</label>
          <input
            id="who"
            value={usernameOrEmail}
            onChange={(event) => setUsernameOrEmail(event.target.value)}
            autoComplete="username"
            required
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
