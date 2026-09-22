/**
 * The wire contract, typed.
 *
 * Mirrors `com.example.kido.admin.dto.DashboardDtos` field for field. When that
 * file changes, this one changes with it — nothing here is inferred at runtime,
 * so a renamed field is a compile error rather than an `undefined` on a tile.
 */

export type UserStats = {
  total: number
  parents: number
  children: number
  premium: number
  newToday: number
  newLast7Days: number
  newLast30Days: number
  profiles: number
  activeToday: number
  activeLast7Days: number
  activeLast30Days: number
}

export type ApplicationStats = {
  platform: string
  users: number
  activeLast7Days: number
  activeLast30Days: number
  loginsLast30Days: number
  lastSeenAt: string | null
}

export type LibraryCategory = {
  type: string
  label: string
  items: number
  bytes: number
}

export type LibraryStats = {
  movies: number
  anime: number
  series: number
  videoSongs: number
  homeVideos: number
  music: number
  photos: number
  adult: number
  totalItems: number
  totalBytes: number
  missingItems: number
  addedLast7Days: number
  byType: LibraryCategory[]
}

export type Count = { key: string; count: number }

export type PosterStats = {
  templates: number
  published: number
  unpublished: number
  byCategory: Count[]
  components: number
  componentsByType: Count[]
}

export type ContentStats = { items: number; published: number }

export type CatalogStats = {
  library: LibraryStats
  posters: PosterStats
  content: ContentStats
}

export type Engagement = {
  liveStreams: number
  streamingNow: number
  peakConcurrentStreams: number
  watchHoursLast7Days: number
  watchHoursThisMonth: number
}

export type Dashboard = {
  generatedAt: string
  users: UserStats
  applications: ApplicationStats[]
  catalog: CatalogStats
  engagement: Engagement
}

export type Account = {
  id: string
  username: string
  email: string
  displayName: string | null
  role: string
}

export type Credentials = {
  token: string
  adminKey: string
  account: Account
}

/**
 * Where the backend is.
 *
 * Empty by default, which makes every call same-origin and lets the dev server
 * proxy `/api` to the running Spring app — the browser then has no CORS
 * preflight to fail on, and a built console can be served by any host that
 * reverse-proxies the API under the same origin. `VITE_API_BASE` overrides it
 * for the case where it cannot be.
 */
const BASE: string = import.meta.env.VITE_API_BASE ?? ''

/** The error envelope every failure comes back in — see GlobalExceptionHandler. */
type ErrorBody = { status?: number; error?: string; message?: string }

export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
    this.name = 'ApiError'
  }
}

async function failure(response: Response): Promise<ApiError> {
  let message = response.statusText
  try {
    const body: ErrorBody = await response.json()
    if (body.message) {
      message = body.message
    }
  } catch {
    // A proxy or a dead backend answers with something that is not our envelope.
    // The status line is then all there is to report, which is still the truth.
  }
  return new ApiError(response.status, message)
}

/** Exchanges a password for an access token. */
export async function signIn(usernameOrEmail: string, password: string) {
  const response = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ usernameOrEmail, password }),
  })
  if (!response.ok) {
    throw await failure(response)
  }
  const body: { token: string; user: Account } = await response.json()
  return body
}

async function get<T>(path: string, credentials: Credentials): Promise<T> {
  const response = await fetch(`${BASE}/api/admin${path}`, {
    headers: {
      Authorization: `Bearer ${credentials.token}`,
      'X-Admin-Key': credentials.adminKey,
    },
  })
  if (!response.ok) {
    throw await failure(response)
  }
  return response.json() as Promise<T>
}

export const fetchDashboard = (credentials: Credentials) =>
  get<Dashboard>('/dashboard', credentials)

/**
 * The live tile on its own.
 *
 * The whole dashboard re-counts a four-figure poster catalog; this does not, so
 * the panel that refreshes every few seconds asks for this instead.
 */
export const fetchEngagement = (credentials: Credentials) =>
  get<Engagement>('/engagement', credentials)
