import { useEffect, useState } from 'react'
import { ApiError, deleteRequest, getJson, postJson } from '../../api/http'
import { AppBar } from '../../app/AppBar'
import { formatDateTime } from '../../lib/dates'
import { displayPaths, saveDisplayId, type DisplayStatus } from './displayApi'
import { appBarTitle } from '../../styles/ui'

const STATUS_POLL_MS = 2000

interface CustomerDisplayScreenProps {
  displayId: number | null
  onDisplayChange: (id: number | null) => void
  onOpenMenu: () => void
}

/**
 * Customer Display settings: set up the customer display for this till (an access
 * code to enter on the display), see whether it is connected, or disconnect it.
 */
export function CustomerDisplayScreen({ displayId, onDisplayChange, onOpenMenu }: CustomerDisplayScreenProps) {
  const [status, setStatus] = useState<DisplayStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  // Watch the display connect (and stay connected).
  useEffect(() => {
    if (displayId === null) return
    let stopped = false
    let timer: number | undefined
    async function poll() {
      try {
        const next = await getJson<DisplayStatus>(displayPaths.display(displayId!))
        if (!stopped) setStatus(next)
      } catch (caught) {
        if (stopped) return
        if (caught instanceof ApiError && caught.status === 404) {
          // Disconnected elsewhere: forget it.
          saveDisplayId(null)
          onDisplayChange(null)
          return
        }
      }
      if (!stopped) timer = window.setTimeout(poll, STATUS_POLL_MS)
    }
    void poll()
    return () => {
      stopped = true
      window.clearTimeout(timer)
    }
  }, [displayId, onDisplayChange])

  async function setUp() {
    setBusy(true)
    setError(null)
    try {
      const created = await postJson<DisplayStatus>(displayPaths.displays, {})
      saveDisplayId(created.id)
      setStatus(created)
      onDisplayChange(created.id)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Could not reach the server. Try again.')
    } finally {
      setBusy(false)
    }
  }

  async function disconnect() {
    if (displayId === null) return
    setBusy(true)
    try {
      await deleteRequest(displayPaths.display(displayId))
    } catch {
      // Already gone; forget it either way.
    }
    saveDisplayId(null)
    setStatus(null)
    onDisplayChange(null)
    setBusy(false)
  }

  const current = displayId !== null && status?.id === displayId ? status : null
  const displayUrl = `${window.location.origin}/display.html`

  return (
    <div className="grid min-h-dvh grid-rows-[1fr_auto] bg-zinc-100">
      <section className="mx-auto my-8 flex w-[min(36rem,calc(100%-2rem))] self-start flex-col items-start gap-4 rounded-3xl bg-white p-8 shadow-sm ring-1 ring-zinc-900/5 max-md:my-4 max-md:p-5" aria-label="Customer Display">
        <span className="grid size-12 place-items-center rounded-2xl bg-linear-to-br from-indigo-500 to-fuchsia-500 text-white shadow-lg shadow-indigo-500/30" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} className="size-6"><path strokeLinecap="round" strokeLinejoin="round" d="M9 17.25v1.007a3 3 0 0 1-.879 2.122L7.5 21h9l-.621-.621A3 3 0 0 1 15 18.257V17.25m6-12V15a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 15V5.25m18 0A2.25 2.25 0 0 0 18.75 3H5.25A2.25 2.25 0 0 0 3 5.25m18 0V12a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 12V5.25" /></svg>
        </span>
        <h2 className="text-2xl font-bold tracking-tight text-zinc-900">Customer Display</h2>
        {displayId === null ? (
          <>
            <p className="text-sm leading-relaxed text-zinc-600">
              A second iPad facing the customer shows posters until you open a sale (New Sale, or the first item),
              then their order. While a sale is open, members log in or sign up there by phone number and are added
              to the sale straight away.
            </p>
            <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void setUp()}>
              Set up a customer display
            </button>
          </>
        ) : current === null ? (
          <p className="text-sm text-zinc-500">Loading…</p>
        ) : current.connected ? (
          <>
            <p className="inline-flex flex-wrap items-center gap-x-1.5 rounded-full bg-green-50 px-3.5 py-1.5 text-sm font-semibold text-green-700 ring-1 ring-green-200 ring-inset before:size-2 before:rounded-full before:bg-green-500 before:content-['']" role="status">
              Connected
              {current.displaySeenAt && <span className="font-normal text-green-700/70"> · last seen {formatDateTime(current.displaySeenAt)}</span>}
            </p>
            <button type="button" className="btn btn-danger" disabled={busy} onClick={() => void disconnect()}>
              Disconnect
            </button>
          </>
        ) : (
          <>
            <p className="text-sm leading-relaxed text-zinc-600">
              On the customer iPad, open <strong>{displayUrl}</strong> and enter this access code:
            </p>
            <p className="w-full rounded-2xl bg-zinc-900 py-6 text-center font-mono text-[3rem] font-bold tracking-[0.35em] text-white shadow-inner" aria-label="Access code">{current.accessCode}</p>
            <p className="flex items-center gap-2 text-sm text-zinc-500" role="status"><span className="size-4 animate-spin rounded-full border-2 border-zinc-200 border-t-indigo-600" aria-hidden="true" />Waiting for the display to connect…</p>
            <button type="button" className="btn" disabled={busy} onClick={() => void disconnect()}>
              Cancel
            </button>
          </>
        )}
        {error && <p className="text-sm font-medium text-red-600" role="alert">{error}</p>}
      </section>

      <AppBar label="Customer Display" onOpenMenu={onOpenMenu}>
        <p className={appBarTitle}>Customer Display</p>
      </AppBar>
    </div>
  )
}
