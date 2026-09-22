import { useCallback, useEffect, useState } from 'react'
import {
  ApiError,
  fetchRow,
  fetchRows,
  fetchTables,
  type Credentials,
  type DbColumn,
  type DbPage,
  type DbRow,
  type DbTable,
  type DbValue,
} from '../api'
import { plain } from '../format'

const PAGE_SIZES = [25, 50, 100, 200]

/**
 * The database browser: pick a table, page through its rows, open one.
 *
 * <p>A grid rather than anything cleverer, because this view has no idea what it
 * is showing — it renders whatever columns the server reports. What it does add is
 * the one thing a raw grid cannot say for itself: every header carries how that
 * column is being handled, so a blank cell under a secret is legibly a refusal
 * rather than an empty field.
 */
export function Records({
  credentials,
  onSignOut,
}: {
  credentials: Credentials
  onSignOut: () => void
}) {
  const [tables, setTables] = useState<DbTable[] | null>(null)
  const [table, setTable] = useState<string | null>(null)
  const [filter, setFilter] = useState('')
  const [page, setPage] = useState<DbPage | null>(null)
  const [pageNumber, setPageNumber] = useState(0)
  const [size, setSize] = useState(25)
  const [sort, setSort] = useState<string | undefined>(undefined)
  const [direction, setDirection] = useState<'asc' | 'desc'>('asc')
  const [query, setQuery] = useState('')
  const [search, setSearch] = useState('')
  const [open, setOpen] = useState<DbRow | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  const report = useCallback(
    (failure: unknown) => {
      if (failure instanceof ApiError && (failure.status === 401 || failure.status === 403)) {
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

  // The table list, once. It carries a count per table, which is a query each,
  // so it is not something to re-ask for on every keystroke in the filter box.
  useEffect(() => {
    let current = true
    void (async () => {
      try {
        const found = await fetchTables(credentials)
        if (current) {
          setTables(found)
          setTable((chosen) => chosen ?? found.find((row) => row.name === 'users')?.name ?? null)
          setError(null)
        }
      } catch (failure) {
        if (current) {
          report(failure)
        }
      }
    })()
    return () => {
      current = false
    }
  }, [credentials, report])

  // A search is a query per keystroke otherwise, against every text column of a
  // table that may have a hundred thousand rows.
  useEffect(() => {
    const timer = window.setTimeout(() => setSearch(query.trim()), 300)
    return () => window.clearTimeout(timer)
  }, [query])

  useEffect(() => {
    if (!table) {
      return
    }
    let current = true
    void (async () => {
      setLoading(true)
      try {
        const next = await fetchRows(credentials, table, {
          page: pageNumber,
          size,
          sort,
          direction,
          query: search,
        })
        if (current) {
          setPage(next)
          setError(null)
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
  }, [credentials, table, pageNumber, size, sort, direction, search, report])

  function choose(name: string) {
    setTable(name)
    setPageNumber(0)
    setSort(undefined)
    setDirection('asc')
    setQuery('')
    setSearch('')
    setOpen(null)
    setPage(null)
  }

  function sortBy(column: DbColumn) {
    if (column.handling === 'SECRET' || column.handling === 'LARGE') {
      return
    }
    if (sort === column.name) {
      setDirection((was) => (was === 'asc' ? 'desc' : 'asc'))
    } else {
      setSort(column.name)
      setDirection('asc')
    }
    setPageNumber(0)
  }

  async function reveal(row: Record<string, DbValue>) {
    if (!page || !tables) {
      return
    }
    const key = tables.find((one) => one.name === page.table)?.primaryKey ?? []
    if (key.length !== 1) {
      setError(
        `'${page.table}' has ${key.length === 0 ? 'no primary key' : 'a composite primary key'}, ` +
          'so a single row cannot be opened.',
      )
      return
    }
    try {
      setOpen(await fetchRow(credentials, page.table, String(row[key[0]])))
      setError(null)
    } catch (failure) {
      report(failure)
    }
  }

  const shown = (tables ?? []).filter((one) =>
    one.name.toLowerCase().includes(filter.trim().toLowerCase()),
  )
  const columns = forReading(page?.columns ?? [])

  return (
    <div className="records">
      <aside className="tables">
        <input
          className="filter"
          value={filter}
          onChange={(event) => setFilter(event.target.value)}
          placeholder="Filter tables"
          aria-label="Filter tables"
        />
        {tables === null ? (
          <p className="meta">Reading the schema…</p>
        ) : (
          <ul>
            {shown.map((one) => (
              <li key={one.name}>
                <button
                  className={one.name === table ? 'table-pick current' : 'table-pick'}
                  onClick={() => choose(one.name)}
                >
                  <span className="name">{one.name}</span>
                  <span className="count">{plain(one.rows)}</span>
                </button>
              </li>
            ))}
            {shown.length === 0 ? <li className="meta">No table matches.</li> : null}
          </ul>
        )}
      </aside>

      <section className="rows">
        {error ? (
          <p className="banner" role="alert">
            <span className="icon" aria-hidden="true">
              !
            </span>
            <span>{error}</span>
          </p>
        ) : null}

        {!table ? (
          <p className="empty">Pick a table.</p>
        ) : (
          <>
            <header className="rows-bar">
              <h2>{table}</h2>
              <input
                value={query}
                onChange={(event) => {
                  setQuery(event.target.value)
                  setPageNumber(0)
                }}
                placeholder="Search text columns"
                aria-label={`Search ${table}`}
              />
              <span className="spacer" />
              <span className="meta">
                {page ? `${plain(page.total)} row${page.total === 1 ? '' : 's'}` : '…'}
              </span>
              <select
                value={size}
                onChange={(event) => {
                  setSize(Number(event.target.value))
                  setPageNumber(0)
                }}
                aria-label="Rows per page"
              >
                {PAGE_SIZES.map((option) => (
                  <option key={option} value={option}>
                    {option} / page
                  </option>
                ))}
              </select>
              <button
                className="btn"
                onClick={() => setPageNumber((was) => Math.max(0, was - 1))}
                disabled={!page || page.page === 0}
              >
                Prev
              </button>
              <span className="meta">
                {page ? `${page.page + 1} / ${Math.max(1, page.totalPages)}` : '—'}
              </span>
              <button
                className="btn"
                onClick={() => setPageNumber((was) => was + 1)}
                disabled={!page || page.page + 1 >= page.totalPages}
              >
                Next
              </button>
            </header>

            {page && page.masked ? (
              <p className="meta masked-note">
                Email addresses and IPs are masked in this list, and password and token
                hashes are never sent. Open a row to see its personal fields in full.
              </p>
            ) : null}

            <div className="grid-scroll">
              <table className="records-grid">
                <thead>
                  <tr>
                    <th scope="col" className="opener" />
                    {columns.map((column) => (
                      <th
                        key={column.name}
                        scope="col"
                        className={
                          column.handling === 'SECRET' || column.handling === 'LARGE'
                            ? 'nosort'
                            : 'sortable'
                        }
                        onClick={() => sortBy(column)}
                        title={`${column.type}${column.nullable ? '' : ' · not null'}`}
                      >
                        <span className="col-name">
                          {column.primaryKey ? <span className="pk">key</span> : null}
                          {column.name}
                          {sort === column.name ? (direction === 'asc' ? ' ↑' : ' ↓') : ''}
                        </span>
                        <span className={`handling ${column.handling.toLowerCase()}`}>
                          {column.handling === 'PLAIN' ? column.type : column.handling}
                        </span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {(page?.rows ?? []).map((row, index) => (
                    <tr key={index}>
                      <td className="opener">
                        <button className="link" onClick={() => void reveal(row)}>
                          open
                        </button>
                      </td>
                      {columns.map((column) => (
                        <td key={column.name} className={`cell ${column.handling.toLowerCase()}`}>
                          <Cell value={row[column.name]} column={column} />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
              {page && page.rows.length === 0 && !loading ? (
                <p className="empty">
                  {search ? `Nothing in ${table} matches “${search}”.` : `${table} is empty.`}
                </p>
              ) : null}
              {loading && !page ? <p className="empty">Reading…</p> : null}
            </div>
          </>
        )}
      </section>

      {open ? <RowPanel row={open} onClose={() => setOpen(null)} /> : null}
    </div>
  )
}

/**
 * Column order for reading, which is not the order the schema stores them in.
 *
 * Hibernate writes its DDL grouped by type, so `users` opens on six booleans and
 * integers and puts `id`, `username` and `email` past the right edge — the three
 * columns anyone opening the table came to see. Key first, then the text that
 * names a row, then the rest; within each group the schema's own order stands.
 *
 * The API is left reporting the schema as it is. This is a reading order, and it
 * belongs to the thing doing the reading.
 */
function forReading(columns: DbColumn[]): DbColumn[] {
  const rank = (column: DbColumn) => {
    if (column.primaryKey) {
      return 0
    }
    return column.handling === 'PERSONAL' || /CHAR|TEXT|VARYING/i.test(column.type) ? 1 : 2
  }
  return columns.map((column, index) => ({ column, index })).sort((left, right) =>
    rank(left.column) - rank(right.column) || left.index - right.index,
  ).map((entry) => entry.column)
}

/**
 * One cell.
 *
 * The three non-plain cases all render as a dash with a reason, because an empty
 * cell that means "withheld" and an empty cell that means "null" must not look
 * the same — an operator reading this is trying to find out what is in the
 * database, and a blank that lies about it is worse than no view at all.
 */
function Cell({ value, column }: { value: DbValue; column: DbColumn }) {
  if (column.handling === 'SECRET') {
    return <span className="withheld">•••</span>
  }
  if (value === null || value === undefined) {
    return column.handling === 'LARGE' ? (
      <span className="withheld">(not loaded)</span>
    ) : (
      <span className="null">null</span>
    )
  }
  if (typeof value === 'boolean') {
    return <span>{value ? 'true' : 'false'}</span>
  }
  return <span>{String(value)}</span>
}

/** One row, opened on purpose, with its personal columns whole. */
function RowPanel({ row, onClose }: { row: DbRow; onClose: () => void }) {
  return (
    <aside className="row-panel" role="dialog" aria-label={`Row in ${row.table}`}>
      <header>
        <div>
          <p className="eyebrow">{row.table}</p>
          <h2>Row</h2>
        </div>
        <span className="spacer" />
        <button className="btn" onClick={onClose}>
          Close
        </button>
      </header>
      <p className="meta">
        Personal fields are shown in full here. Password and token hashes are still not
        sent — revealing an address is not revealing a credential.
      </p>
      <dl>
        {forReading(row.columns).map((column) => (
          <div key={column.name}>
            <dt>
              {column.name}
              <span className={`handling ${column.handling.toLowerCase()}`}>
                {column.handling === 'PLAIN' ? column.type : column.handling}
              </span>
            </dt>
            <dd>
              <Cell value={row.values[column.name]} column={column} />
            </dd>
          </div>
        ))}
      </dl>
    </aside>
  )
}
