import { useEffect, useState } from 'react'
import { fetchMirrorOperations, type Credentials, type MirrorOperations } from '../api'
import { plain } from '../format'
import { Tile } from './Tiles'

function counts(values: Record<string, number>) {
  return Object.entries(values).map(([name, value]) => `${name} ${plain(value)}`).join(' · ') || '—'
}

export function MirrorOperationsPanel({ credentials, live }: { credentials: Credentials; live: boolean }) {
  const [data, setData] = useState<MirrorOperations | null>(null)
  const [unavailable, setUnavailable] = useState(false)

  useEffect(() => {
    let stopped = false
    let timer: ReturnType<typeof setTimeout>
    const refresh = async () => {
      try {
        const snapshot = await fetchMirrorOperations(credentials)
        if (!stopped) {
          setData(snapshot)
          setUnavailable(false)
        }
      } catch {
        if (!stopped) setUnavailable(true)
      } finally {
        if (!stopped && live) timer = setTimeout(refresh, 15_000)
      }
    }
    void refresh()
    return () => {
      stopped = true
      clearTimeout(timer)
    }
  }, [credentials, live])

  return (
    <section className="section" aria-labelledby="mirror-operations">
      <p className="eyebrow" id="mirror-operations">Mirror operations</p>
      {unavailable ? <p className="meta">Mirror operations data is unavailable.</p>
        : !data ? <p className="meta">Loading mirror operations…</p>
        : (
          <>
            <div className="grid">
              <Tile label="Upstream session" value={data.sessionState} sub={data.observedAvailability} />
              <Tile label="Recovery" value={data.recovery.state}
                sub={data.recovery.lastSuccessAt ? `Last success ${new Date(data.recovery.lastSuccessAt).toLocaleString()}` : 'No successful recovery recorded'} />
              <Tile label="Active playback HTTP requests" value={plain(data.activePlaybackRequests)}
                sub={`${plain(data.upstreamInFlight)} upstream in flight · ${Math.ceil(data.cooldownSeconds)}s cooldown`} />
              <Tile label="Playback HTTP errors since startup" value={`${data.playbackErrorPercent.toFixed(1)}%`}
                sub={`${plain(data.playbackErrors)} of ${plain(data.playbackRequests)} requests`} />
            </div>
            <dl className="meta">
              {data.recovery.incidentId && <><dt>Recovery incident</dt><dd>{data.recovery.incidentId} · {data.recovery.outcome ?? 'Pending'}</dd></>}
              {Object.entries(data.executors).map(([name, lane]) => (
                <div key={name}><dt>{name} executor</dt><dd>{plain(lane.active)} active · {plain(lane.queued)} queued · {plain(lane.rejected)} rejected · {plain(lane.expired)} expired</dd></div>
              ))}
              <dt>Recovery outcomes</dt><dd>{counts(data.recoveryOutcomes)}</dd>
              <dt>Master cache</dt><dd>{counts(data.masterCache)}</dd>
              <dt>Home cache</dt><dd>{counts(data.homeCache)}</dd>
              <dt>Token refresh</dt><dd>{counts(data.tokenRefresh)}</dd>
              <dt>HTTP errors by status</dt><dd>{counts(data.playbackErrorsByStatus)}</dd>
            </dl>
          </>
        )}
    </section>
  )
}
