import { useCallback, useEffect, useState } from 'react'
import { ApiError, getJson, postJson } from '../api/http'
import { formatTime } from '../lib/dates'
import { noticeClass, sectionTitle, table, tableFrame } from './styles'
import { dashboardPaths, type PinLock } from './types'

/** How often the list is checked while it is on screen. */
const REFRESH_MS = 10_000

/**
 * PIN pads locked by too many wrong PINs in a row (the till's PIN login, Clock In/Out, or setting up
 * an iPad). They unlock by themselves after a few minutes; unlocking one here lets staff carry on at
 * once.
 */
export function PinLocksPanel() {
  const [locks, setLocks] = useState<PinLock[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  const load = useCallback(() => {
    getJson<PinLock[]>(dashboardPaths.pinLocks)
      .then((data) => {
        setLocks(data)
        setLoadError(null)
      })
      .catch((error: unknown) => setLoadError(error instanceof ApiError ? error.message : 'Offline'))
  }, [])

  useEffect(() => {
    load()
    const timer = window.setInterval(load, REFRESH_MS)
    return () => window.clearInterval(timer)
  }, [load])

  async function unlock(lock: PinLock) {
    const id = `${lock.outletId}-${lock.pad}`
    setBusy(id)
    setStatus(null)
    try {
      await postJson(dashboardPaths.pinUnlock(lock.outletId, lock.pad), {})
      setStatus({ tone: 'ok', text: `${lock.padName} at ${lock.outletName} is unlocked. Staff can enter their PIN again.` })
      load()
    } catch (error) {
      setStatus({ tone: 'error', text: error instanceof ApiError ? error.message : 'Offline' })
    } finally {
      setBusy(null)
    }
  }

  return (
    <section aria-labelledby="pin-locks-title">
      <h2 id="pin-locks-title" className={sectionTitle}>Locked PIN pads</h2>
      <p className="mb-3 text-sm text-zinc-500">
        After 10 wrong PINs in a row, that PIN pad at the outlet locks for 5 minutes. Unlock it here so staff don’t have to
        wait.
      </p>
      {status && (
        <p className={`mb-3 ${noticeClass(status.tone)}`} role="status">
          {status.text}
        </p>
      )}
      {locks === null ? (
        <p className="text-sm text-zinc-500">{loadError ?? 'Loading…'}</p>
      ) : locks.length === 0 ? (
        <p className="flex items-center gap-2 rounded-2xl bg-white px-5 py-4 text-sm font-medium text-zinc-500 shadow-sm ring-1 ring-zinc-900/5 before:size-2 before:rounded-full before:bg-green-500 before:content-['']">No PIN pads are locked.</p>
      ) : (
        <div className={tableFrame}>
          <table className={table}>
            <thead>
              <tr>
                <th>Outlet</th>
                <th>PIN pad</th>
                <th>Locked until</th>
                <th aria-label="Unlock" />
              </tr>
            </thead>
            <tbody>
              {locks.map((lock) => (
                <tr key={`${lock.outletId}-${lock.pad}`}>
                  <td className="font-semibold">{lock.outletName}</td>
                  <td>{lock.padName}</td>
                  <td>{formatTime(lock.lockedUntil)}</td>
                  <td className="text-right">
                    <button
                      type="button"
                      className="btn btn-primary"
                      aria-label={`Unlock ${lock.padName} at ${lock.outletName}`}
                      disabled={busy !== null}
                      onClick={() => void unlock(lock)}
                    >
                      Unlock
                    </button>
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
