import { useCallback, useEffect, useState, type ReactNode } from 'react'
import {
  ApiError,
  fetchDashboard,
  fetchEngagement,
  type Credentials,
  type Dashboard as DashboardData,
} from '../api'
import { bytes, clockTime, compact, hours, plain, titleCase } from '../format'
import { Applications } from './Applications'
import { BarList, type Bar } from './BarList'
import { Records } from './Records'
import { Card, Hero, Tile } from './Tiles'

/** The two things this console does: read the summary, or read the rows. */
type View = 'overview' | 'records'

/** How often the live panel re-asks. Only the cheap endpoint is polled. */
const LIVE_INTERVAL_MS = 15_000

export function Dashboard({
  credentials,
  onSignOut,
}: {
  credentials: Credentials
  onSignOut: () => void
}) {
  const [data, setData] = useState<DashboardData | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [live, setLive] = useState(true)
  const [lastLoadedAt, setLastLoadedAt] = useState<string | null>(null)
  const [view, setView] = useState<View>('overview')

  const report = useCallback(
    (failure: unknown) => {
      if (failure instanceof ApiError && (failure.status === 401 || failure.status === 403)) {
        // The token expired, or the key was changed under us. Either way the
        // credentials in hand are no longer credentials.
        onSignOut()
        return
      }
      setError(
        failure instanceof ApiError
          ? `${failure.status} — ${failure.message}`
          : 'Could not reach the server.',
      )
    },
    [onSignOut],
  )

  const received = useCallback((next: DashboardData) => {
    setData(next)
    setError(null)
    setLastLoadedAt(new Date().toISOString())
  }, [])

  // Load once per set of credentials, and ignore a response that arrives after
  // this console has signed out or swapped credentials underneath it.
  useEffect(() => {
    let current = true
    void (async () => {
      try {
        const next = await fetchDashboard(credentials)
        if (current) {
          received(next)
        }
      } catch (failure) {
        if (current) {
          report(failure)
        }
      } finally {
        if (current) {
          setLoading(false)
        }
      }
    })()
    return () => {
      current = false
    }
  }, [credentials, received, report])

  /** The Refresh button. Unlike the mount load, this one shows it is working. */
  async function refresh() {
    setLoading(true)
    try {
      received(await fetchDashboard(credentials))
    } catch (failure) {
      report(failure)
    } finally {
      setLoading(false)
    }
  }

  // The live panel refreshes on its own, and only it: re-counting a four-figure
  // poster catalog every fifteen seconds to find out whether anyone is watching
  // something would be the whole page paying for one tile.
  //
  // The timer depends on *whether* there is data, never on the data itself, so
  // a tick does not tear down and rebuild the interval it was fired from. The
  // update folds into whatever is current at that moment rather than into the
  // snapshot this effect closed over.
  const loaded = data !== null
  useEffect(() => {
    if (!live || !loaded) {
      return
    }
    const timer = window.setInterval(async () => {
      try {
        const engagement = await fetchEngagement(credentials)
        setData((current) => (current ? { ...current, engagement } : current))
        setLastLoadedAt(new Date().toISOString())
      } catch (failure) {
        report(failure)
      }
    }, LIVE_INTERVAL_MS)
    return () => window.clearInterval(timer)
  }, [live, loaded, credentials, report])

  const shell = (body: ReactNode, updatedAt: string | null) => (
    <main className="shell">
      <Topbar
        account={credentials.account.displayName ?? credentials.account.username}
        baseUrl={credentials.baseUrl}
        onSignOut={onSignOut}
        onRefresh={() => void refresh()}
        loading={loading}
        live={live}
        setLive={setLive}
        lastLoadedAt={updatedAt}
        view={view}
        setView={setView}
      />
      {error ? <Banner message={error} /> : null}
      {body}
    </main>
  )

  // Records does not wait on the overview's numbers — it reads the schema itself,
  // and someone who came here to look up a row should not sit through a count of
  // the poster catalog first.
  if (view === 'records') {
    return shell(<Records credentials={credentials} onSignOut={onSignOut} />, lastLoadedAt)
  }

  if (!data) {
    return shell(
      <div className="grid">
        <div className="skeleton" />
        <div className="skeleton" />
        <div className="skeleton" />
        <div className="skeleton" />
      </div>,
      null,
    )
  }

  const { users, applications, catalog, engagement } = data
  const { library, posters, content } = catalog

  const categoryBars: Bar[] = library.byType.map((category) => ({
    name: category.label,
    value: category.items,
    detail: `${category.label}: ${plain(category.items)} item(s), ${bytes(category.bytes)}`,
  }))
  const posterBars: Bar[] = posters.byCategory.map((row) => ({
    name: titleCase(row.key),
    value: row.count,
  }))
  const componentBars: Bar[] = posters.componentsByType.map((row) => ({
    name: titleCase(row.key),
    value: row.count,
  }))

  return shell(
    <>
      <section className="section">
        <div className="card">
          <Hero
            value={compact(users.total)}
            caption={`accounts · ${plain(users.profiles)} viewing profiles · ${plain(
              users.parents,
            )} parent, ${plain(users.children)} child`}
          />
        </div>
      </section>

      <section className="section">
        <p className="eyebrow">People</p>
        <div className="grid">
          <Tile
            label="Active today"
            value={plain(users.activeToday)}
            sub={`${plain(users.activeLast7Days)} this week · ${plain(
              users.activeLast30Days,
            )} this month`}
          />
          <Tile
            label="New accounts this week"
            value={plain(users.newLast7Days)}
            sub={
              users.newToday > 0 ? (
                <>
                  <span className="up">+{plain(users.newToday)}</span> today
                </>
              ) : (
                'none today'
              )
            }
          />
          <Tile
            label="Paying accounts"
            value={plain(users.premium)}
            sub={
              users.total > 0
                ? `${Math.round((users.premium / users.total) * 100)}% of accounts, term still live`
                : 'no accounts yet'
            }
          />
          <Tile
            label="Watching now"
            value={plain(engagement.streamingNow)}
            sub={`${plain(engagement.liveStreams)} stream(s) · peak ${plain(
              engagement.peakConcurrentStreams,
            )} at once`}
          />
        </div>
        <p className="meta" style={{ marginTop: 10 }}>
          Active means signed in — most of what the apps do never starts a stream, so
          watch data cannot answer it.
        </p>
      </section>

      <section className="section">
        <p className="eyebrow">Applications</p>
        <Applications apps={applications} />
      </section>

      <section className="section">
        <p className="eyebrow">Catalog</p>
        <div className="grid" style={{ marginBottom: 'var(--gap)' }}>
          <Tile
            label="Movies"
            value={plain(library.movies)}
            sub={`${plain(library.anime)} anime · ${plain(library.series)} series`}
          />
          <Tile
            label="Music"
            value={plain(library.music)}
            sub={`${plain(library.videoSongs)} video songs`}
          />
          <Tile
            label="Poster templates"
            value={plain(posters.templates)}
            sub={`${plain(posters.published)} published · ${plain(posters.components)} components`}
          />
          <Tile
            label="Library on disk"
            value={bytes(library.totalBytes)}
            sub={`${plain(library.totalItems)} items · ${plain(library.addedLast7Days)} added this week`}
          />
        </div>

        <div className="grid wide">
          <Card title="Items by category" note="hidden and missing rows excluded">
            <BarList bars={categoryBars} format={plain} />
            {library.missingItems > 0 ? (
              <p className="meta" style={{ marginTop: 12, marginBottom: 0 }}>
                {plain(library.missingItems)} row(s) no longer on disk, kept in case a drive is
                only unmounted.
              </p>
            ) : null}
          </Card>

          <Card title="Poster templates by ceremony">
            <BarList bars={posterBars} format={plain} />
            {componentBars.length > 0 ? (
              <>
                <p className="eyebrow" style={{ margin: '18px 0 10px' }}>
                  Components
                </p>
                <BarList bars={componentBars} format={plain} />
              </>
            ) : null}
          </Card>
        </div>
      </section>

      <section className="section">
        <p className="eyebrow">Usage</p>
        <div className="grid">
          <Tile
            label="Watched this month"
            value={hours(engagement.watchHoursThisMonth)}
            sub={`${hours(engagement.watchHoursLast7Days)} in the last 7 days`}
          />
          <Tile
            label="Kids-app content"
            value={plain(content.items)}
            sub={`${plain(content.published)} published`}
          />
          <Tile
            label="Profiles per account"
            value={users.total > 0 ? (users.profiles / users.total).toFixed(1) : '—'}
            sub={`${plain(users.profiles)} profiles across ${plain(users.total)} accounts`}
          />
          <Tile
            label="Counted at"
            value={clockTime(data.generatedAt)}
            sub={live ? `refreshing live every ${LIVE_INTERVAL_MS / 1000}s` : 'live refresh off'}
          />
        </div>
      </section>
    </>,
    lastLoadedAt,
  )
}

function Banner({ message }: { message: string }) {
  return (
    <p className="banner" role="alert" style={{ marginBottom: 20 }}>
      <span className="icon" aria-hidden="true">
        !
      </span>
      <span>{message}</span>
    </p>
  )
}

/**
 * The address, short enough to sit in a header.
 *
 * Host only: the scheme is noise once it is working, and the path is always
 * empty. Blank means same-origin, which is the dev proxy or a reverse proxy —
 * "this host" is the honest name for it, since the console cannot tell which.
 */
function serverLabel(baseUrl: string): string {
  if (baseUrl === '') {
    return 'this host'
  }
  try {
    return new URL(baseUrl).host
  } catch {
    // Stored by an older build, or hand-edited. Showing it raw beats throwing
    // inside a header.
    return baseUrl
  }
}

function Topbar({
  account,
  baseUrl,
  onRefresh,
  onSignOut,
  loading,
  live,
  setLive,
  lastLoadedAt,
  view,
  setView,
}: {
  account: string
  baseUrl: string
  onRefresh: () => void
  onSignOut: () => void
  loading: boolean
  live: boolean
  setLive: (value: boolean) => void
  lastLoadedAt: string | null
  view: View
  setView: (value: View) => void
}) {
  const overview = view === 'overview'
  return (
    <header className="topbar">
      <div>
        <p className="eyebrow">Kido</p>
        <h1>Admin console</h1>
      </div>

      <nav className="tabs" aria-label="Views">
        <button
          className={overview ? 'tab current' : 'tab'}
          onClick={() => setView('overview')}
          aria-current={overview ? 'page' : undefined}
        >
          Overview
        </button>
        <button
          className={overview ? 'tab' : 'tab current'}
          onClick={() => setView('records')}
          aria-current={overview ? undefined : 'page'}
        >
          Records
        </button>
      </nav>

      <span className="spacer" />
      <span className="meta">
        {/* Which server this is. Worth a few characters on screen now that the
            address is typed in rather than built in: a local dev database and
            somebody's actual household look identical once the numbers land,
            and the Records grid makes that a difference worth seeing. */}
        {serverLabel(baseUrl)} · {account}
        {lastLoadedAt && overview ? ` · updated ${clockTime(lastLoadedAt)}` : ''}
      </span>
      {/* Both only act on the overview's numbers, so they are hidden rather than
          left on screen doing nothing while the records grid is up. */}
      {overview ? (
        <>
          <label className="toggle">
            <input
              type="checkbox"
              checked={live}
              onChange={(event) => setLive(event.target.checked)}
            />
            Live
          </label>
          <button className="btn" onClick={onRefresh} disabled={loading}>
            {loading ? 'Refreshing…' : 'Refresh'}
          </button>
        </>
      ) : null}
      <ThemeToggle />
      <button className="btn" onClick={onSignOut}>
        Sign out
      </button>
    </header>
  )
}

/**
 * Follows the OS until someone says otherwise, then stays where they put it.
 * The stamp goes on `<html>`, which is the scope the dark tokens are declared
 * against, so it wins over the media query in both directions.
 */
function ThemeToggle() {
  const [theme, setTheme] = useState<'light' | 'dark' | null>(
    () => (localStorage.getItem('kido-admin-theme') as 'light' | 'dark' | null) ?? null,
  )

  useEffect(() => {
    if (theme) {
      document.documentElement.dataset.theme = theme
      localStorage.setItem('kido-admin-theme', theme)
    } else {
      delete document.documentElement.dataset.theme
    }
  }, [theme])

  const dark =
    theme === 'dark' ||
    (theme === null && window.matchMedia('(prefers-color-scheme: dark)').matches)

  return (
    <button
      className="btn"
      onClick={() => setTheme(dark ? 'light' : 'dark')}
      aria-label={dark ? 'Switch to light theme' : 'Switch to dark theme'}
    >
      {dark ? 'Light' : 'Dark'}
    </button>
  )
}
