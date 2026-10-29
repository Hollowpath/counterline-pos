import { useState } from 'react'
import { ApiError, postJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { formatDateTime } from '../lib/dates'
import { PinLocksPanel } from './PinLocksPanel'
import { note, noticeClass, sectionTitle, table, tableAsCards, tableFrame } from './styles'
import { dashboardPaths, type TillDevice } from './types'
import { Page } from './ui'

/**
 * The iPads set up as tills. An iPad is set up once for its outlet, at the till; moving it to
 * another outlet (or replacing it) starts here: reset it, and a manager sets it up again.
 */
export function TillDevicesAdmin() {
  const devices = useApi<TillDevice[]>(dashboardPaths.devices)
  const [confirming, setConfirming] = useState<number | null>(null)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  if (devices.status !== 'success') {
    return <LoadState status={devices.status} message={devices.status === 'error' ? devices.message : undefined} onRetry={devices.reload} />
  }

  async function reset(device: TillDevice) {
    setBusy(true)
    setStatus(null)
    try {
      await postJson(dashboardPaths.deviceReset(device.id), {})
      setStatus({ tone: 'ok', text: `${device.label} at ${device.outletName} was reset. A manager sets it up again at the till.` })
      setConfirming(null)
      devices.reload()
    } catch (error) {
      setStatus({ tone: 'error', text: error instanceof ApiError ? error.message : 'Offline' })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Page
      title="iPads"
      description="Each outlet has one till iPad, set up once. To move or replace it, reset it here: it stops logging staff in, and a manager sets up the new (or the same) iPad at the till."
    >
      {status && (
        <p className={noticeClass(status.tone)} role="status">
          {status.text}
        </p>
      )}
      <section aria-label="Till iPads">
        <h2 className={sectionTitle}>Tills</h2>
        {devices.data.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No iPads are set up yet.</p>
        ) : (
          <div className={tableFrame}>
            <table className={`${table} ${tableAsCards}`}>
              <thead>
                <tr>
                  <th>Outlet</th>
                  <th>iPad</th>
                  <th>Set up</th>
                  <th>Last login</th>
                  <th aria-label="Reset" />
                </tr>
              </thead>
              <tbody>
                {devices.data.map((device) => (
                  <tr key={device.id}>
                    <td className="font-semibold">{device.outletName}</td>
                    <td data-label="iPad">{device.label}</td>
                    <td data-label="Set up">
                      {formatDateTime(device.setUpAt)}
                      <span className={note}>by {device.setUpBy}</span>
                    </td>
                    <td data-label="Last login">{device.lastLoginAt ? formatDateTime(device.lastLoginAt) : '—'}</td>
                    <td className="whitespace-nowrap text-right">
                      {confirming === device.id ? (
                        <>
                          <button type="button" className="btn" disabled={busy} onClick={() => setConfirming(null)}>
                            Keep
                          </button>{' '}
                          <button type="button" className="btn btn-danger" disabled={busy} onClick={() => void reset(device)}>
                            Confirm reset
                          </button>
                        </>
                      ) : (
                        <button type="button" className="btn" aria-label={`Reset ${device.label} at ${device.outletName}`}
                          onClick={() => setConfirming(device.id)}>
                          Reset
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
      <PinLocksPanel />
    </Page>
  )
}
