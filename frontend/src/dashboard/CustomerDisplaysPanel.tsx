import { useState } from 'react'
import { ApiError, postJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { formatDateTime } from '../lib/dates'
import { note, noticeClass, sectionTitle, table, tableAsCards, tableFrame } from './styles'
import { dashboardPaths, type CustomerDisplayRow } from './types'

/**
 * The customer displays at the outlets this user can see, connected or still waiting for their access
 * code (which lasts ten minutes). Disconnecting one (a lost screen, say) ends its session at once; it
 * asks for a new code on the till.
 */
export function CustomerDisplaysPanel() {
  const displays = useApi<CustomerDisplayRow[]>(dashboardPaths.displays)
  const [confirming, setConfirming] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  if (displays.status !== 'success') {
    return <LoadState status={displays.status} message={displays.status === 'error' ? displays.message : undefined} onRetry={displays.reload} />
  }

  async function disconnect(display: CustomerDisplayRow) {
    setBusy(true)
    setStatus(null)
    try {
      await postJson(dashboardPaths.displayDisconnect(display.id), {})
      setStatus({ tone: 'ok', text: `The customer display at ${display.outletName} was disconnected.` })
      setConfirming(null)
      displays.reload()
    } catch (error) {
      setStatus({ tone: 'error', text: error instanceof ApiError ? error.message : 'Offline' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Customer displays">
      <h2 className={sectionTitle}>Customer displays</h2>
      {status && (
        <p className={noticeClass(status.tone)} role="status">
          {status.text}
        </p>
      )}
      {displays.data.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No customer displays are connected.</p>
      ) : (
        <div className={tableFrame}>
          <table className={`${table} ${tableAsCards}`}>
            <thead>
              <tr>
                <th>Outlet</th>
                <th>Status</th>
                <th>Paired</th>
                <th>Last seen</th>
                <th aria-label="Disconnect" />
              </tr>
            </thead>
            <tbody>
              {displays.data.map((display) => (
                <tr key={display.id}>
                  <td className="font-semibold">{display.outletName}</td>
                  <td data-label="Status">{display.connected ? 'Connected' : 'Waiting for its code'}</td>
                  <td data-label="Paired">
                    {display.connectedAt ? formatDateTime(display.connectedAt) : '—'}
                    {!display.connected && <span className={note}>created {formatDateTime(display.createdAt)}</span>}
                  </td>
                  <td data-label="Last seen">{display.lastSeenAt ? formatDateTime(display.lastSeenAt) : '—'}</td>
                  <td className="whitespace-nowrap text-right">
                    {confirming === display.id ? (
                      <>
                        <button type="button" className="btn" disabled={busy} onClick={() => setConfirming(null)}>
                          Keep
                        </button>{' '}
                        <button type="button" className="btn btn-danger" disabled={busy} onClick={() => void disconnect(display)}>
                          Confirm disconnect
                        </button>
                      </>
                    ) : (
                      <button type="button" className="btn" aria-label={`Disconnect the customer display at ${display.outletName}`}
                        onClick={() => setConfirming(display.id)}>
                        Disconnect
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
