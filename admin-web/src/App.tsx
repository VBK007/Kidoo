import { useState } from 'react'
import type { Credentials } from './api'
import { Dashboard } from './components/Dashboard'
import { SignIn } from './components/SignIn'

/**
 * Held in `sessionStorage`, not `localStorage`: this is an access token and an
 * admin key, and closing the tab should end the session rather than leave both
 * on the disk of whichever machine the console was opened on. A reload keeps
 * them, which is the one case worth surviving.
 */
const STORE_KEY = 'kido-admin-credentials'

function stored(): Credentials | null {
  try {
    const raw = sessionStorage.getItem(STORE_KEY)
    if (!raw) {
      return null
    }
    const parsed = JSON.parse(raw) as Credentials
    // A session stored before the address was part of one has no `baseUrl`, and
    // `${undefined}/api/...` is a request to a path beginning "undefined" — a
    // 404 from this origin that reads like the server refusing. Same-origin is
    // what those sessions were, so say so.
    return { ...parsed, baseUrl: parsed.baseUrl ?? '' }
  } catch {
    return null
  }
}

export default function App() {
  const [credentials, setCredentials] = useState<Credentials | null>(stored)

  function signedIn(next: Credentials) {
    sessionStorage.setItem(STORE_KEY, JSON.stringify(next))
    setCredentials(next)
  }

  function signedOut() {
    sessionStorage.removeItem(STORE_KEY)
    setCredentials(null)
  }

  return credentials ? (
    <Dashboard credentials={credentials} onSignOut={signedOut} />
  ) : (
    <SignIn onSignedIn={signedIn} />
  )
}
