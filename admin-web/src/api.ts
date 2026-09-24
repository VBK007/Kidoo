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
  /** Which server these credentials are for — see {@link normaliseBase}. */
  baseUrl: string
}

/**
 * Where the backend is, as a build-time default.
 *
 * Empty by default, which makes every call same-origin and lets the dev server
 * proxy `/api` to the running Spring app — the browser then has no CORS
 * preflight to fail on, and a built console can be served by any host that
 * reverse-proxies the API under the same origin. `VITE_API_BASE` overrides it
 * for the case where it cannot be.
 *
 * This is only what the sign-in form opens on. The address actually used is
 * whatever was typed there, because a home server reached through a free tunnel
 * gets a new hostname every time the tunnel restarts, and a console that can
 * only be repointed by rebuilding it is a console that is wrong most days.
 */
export const DEFAULT_BASE: string = import.meta.env.VITE_API_BASE ?? ''

/**
 * What the form's address field means, in one place.
 *
 * Blank stays blank rather than becoming a URL: that is the same-origin case,
 * where `/api/...` is the whole path and the dev proxy or a reverse proxy does
 * the rest. A bare host gets `https://`, which is what a tunnel hands out; type
 * `http://` explicitly for a LAN address that has no certificate. The trailing
 * slash goes because every caller appends a path that starts with one, and
 * `//api/...` is answered by some proxies and 404ed by others.
 */
export function normaliseBase(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '')
  if (trimmed === '') {
    return ''
  }
  return /^https?:\/\//i.test(trimmed) ? trimmed : `https://${trimmed}`
}

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

/**
 * A request that names the address when there was nothing at it.
 *
 * `fetch` rejects with a bare `TypeError` for a hostname that does not resolve,
 * a refused connection and a CORS wall alike, and "Failed to fetch" on its own
 * sends someone looking at their password. Since the address is now typed in
 * rather than built in, the address is the first thing worth suspecting, so it
 * goes in the message. Status 0 marks "never got an answer", which is not any
 * HTTP status and must not be mistaken for one.
 */
async function reach(url: string, init?: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init)
  } catch {
    throw new ApiError(
      0,
      `Could not reach ${url.replace(/\/api\/.*$/, '') || 'this origin'} — check the server address, and that the server is up.`,
    )
  }
}

/** Exchanges a password for an access token, at the address just typed in. */
export async function signIn(baseUrl: string, usernameOrEmail: string, password: string) {
  const response = await reach(`${baseUrl}/api/auth/login`, {
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

async function fetchJson<T>(path: string, credentials: Credentials): Promise<T> {
  const response = await reach(`${credentials.baseUrl}/api/admin${path}`, {
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
  fetchJson<Dashboard>('/dashboard', credentials)

/**
 * The live tile on its own.
 *
 * The whole dashboard re-counts a four-figure poster catalog; this does not, so
 * the panel that refreshes every few seconds asks for this instead.
 */
export const fetchEngagement = (credentials: Credentials) =>
  fetchJson<Engagement>('/engagement', credentials)

// --- the database browser ---

/**
 * What the server will do with a column's values.
 *
 * `SECRET` never arrives at all, `PERSONAL` arrives masked in a listing and whole
 * in a single row, `LARGE` is described rather than sent. The value under such a
 * column is `null` in a listing — present, so a grid keeps its shape, and empty,
 * because that is the truth about what was sent.
 */
export type Handling = 'PLAIN' | 'PERSONAL' | 'SECRET' | 'LARGE'

export type DbColumn = {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  handling: Handling
  /** Has text to match a sub-string against — so `contains` applies to it. */
  searchable: boolean
}

export type DbTable = {
  name: string
  rows: number
  columnCount: number
  primaryKey: string[]
}

export type DbValue = string | number | boolean | null

export type DbPage = {
  table: string
  columns: DbColumn[]
  rows: Record<string, DbValue>[]
  page: number
  size: number
  total: number
  totalPages: number
  sort: string
  direction: 'asc' | 'desc'
  query: string | null
  filterColumn: string | null
  filterOp: FilterOp | null
  filterValue: string | null
  masked: boolean
}

/**
 * What a filter may ask, mirroring `DatabaseBrowser.FilterOp`.
 *
 * `contains` is a sub-string match and so only means anything on text; the server
 * refuses it on a column that has no sub-string, which is why the picker below
 * offers it only where it applies.
 */
export type FilterOp = 'contains' | 'eq' | 'ne' | 'null' | 'notnull'

export const FILTER_OPS: { value: FilterOp; label: string; needsValue: boolean; textOnly: boolean }[] = [
  { value: 'contains', label: 'contains', needsValue: true, textOnly: true },
  { value: 'eq', label: '=', needsValue: true, textOnly: false },
  { value: 'ne', label: '≠', needsValue: true, textOnly: false },
  { value: 'null', label: 'is empty', needsValue: false, textOnly: false },
  { value: 'notnull', label: 'is not empty', needsValue: false, textOnly: false },
]

export type DbRow = {
  table: string
  columns: DbColumn[]
  values: Record<string, DbValue>
  masked: boolean
}

export const fetchTables = (credentials: Credentials) =>
  fetchJson<DbTable[]>('/db/tables', credentials)

export function fetchRows(
  credentials: Credentials,
  table: string,
  options: {
    page: number
    size: number
    sort?: string
    direction?: string
    query?: string
    filterColumn?: string
    filterOp?: FilterOp
    filterValue?: string
  },
) {
  const params = new URLSearchParams({
    page: String(options.page),
    size: String(options.size),
  })
  if (options.sort) {
    params.set('sort', options.sort)
  }
  if (options.direction) {
    params.set('direction', options.direction)
  }
  if (options.query) {
    params.set('q', options.query)
  }
  if (options.filterColumn) {
    params.set('filter', options.filterColumn)
    if (options.filterOp) {
      params.set('filterOp', options.filterOp)
    }
    if (options.filterValue) {
      params.set('filterValue', options.filterValue)
    }
  }
  return fetchJson<DbPage>(
    `/db/tables/${encodeURIComponent(table)}/rows?${params}`,
    credentials,
  )
}

/** One row by primary key — the deliberate click that unmasks it. */
export const fetchRow = (credentials: Credentials, table: string, id: string) =>
  fetchJson<DbRow>(
    `/db/tables/${encodeURIComponent(table)}/rows/${encodeURIComponent(id)}`,
    credentials,
  )
