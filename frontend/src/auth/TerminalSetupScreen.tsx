import { useState } from 'react'
import { ApiError, pinLockout } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { useAuth } from './AuthContext'
import { PinEntry } from './PinEntry'
import { pinLockStatus } from './pinLock'
import { authPaths, type Outlet } from './types'
import { loginStyles } from '../styles/ui'

/**
 * First run, or after a reset from the dashboard: a manager sets this iPad up for their outlet,
 * once (an outlet has one till: one already set up there is reset from the dashboard first). Staff
 * then log in there, and everything the till records belongs to that outlet.
 */
export function TerminalSetupScreen({ notice }: { notice?: string }) {
  const { setUpTerminal } = useAuth()
  const outlets = useApi<Outlet[]>(authPaths.outlets)
  const [chosen, setChosen] = useState<Outlet | null>(null)

  async function confirm(pin: string) {
    try {
      await setUpTerminal(pin, chosen!)
      // On success the login screen takes over.
    } catch (error) {
      return pinLockout(error) ?? (error instanceof ApiError && (error.status === 403 || error.status === 409 || error.status === 429)
        ? error.message
        : 'Offline')
    }
  }

  return (
    <main className={loginStyles.screen}>
      <div className={loginStyles.panel}>
        <h1 className={loginStyles.title}>Set up this iPad</h1>
        {notice && (
          <p className={loginStyles.notice} role="status">
            {notice}
          </p>
        )}
        {chosen === null ? (
          <>
            <p className={loginStyles.prompt}>Which outlet is this till at?</p>
            {outlets.status !== 'success' ? (
              <LoadState status={outlets.status} message={outlets.status === 'error' ? outlets.message : undefined} onRetry={outlets.reload} />
            ) : (
              <ul className="grid w-full list-none gap-2.5" aria-label="Outlets">
                {outlets.data.map((outlet) => (
                  <li key={outlet.id}>
                    <button type="button" className="group flex w-full items-center gap-3 rounded-2xl bg-white px-4 py-3.5 text-left shadow-xs ring-1 ring-zinc-200 transition ring-inset hover:-translate-y-0.5 hover:shadow-md hover:ring-indigo-300" onClick={() => setChosen(outlet)}>
                      <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-indigo-50 text-indigo-600 ring-1 ring-indigo-100" aria-hidden="true">
                        <svg viewBox="0 0 20 20" fill="currentColor" className="size-5"><path fillRule="evenodd" d="m9.69 18.933.003.001C9.89 19.02 10 19 10 19s.11.02.308-.066l.002-.001.006-.003.018-.008a5.741 5.741 0 0 0 .281-.14c.186-.096.446-.24.757-.433.62-.384 1.445-.966 2.274-1.765C15.302 14.988 17 12.493 17 9A7 7 0 1 0 3 9c0 3.492 1.698 5.988 3.355 7.584a13.731 13.731 0 0 0 2.273 1.765 11.842 11.842 0 0 0 .976.544l.062.029.018.008.006.003ZM10 11.25a2.25 2.25 0 1 0 0-4.5 2.25 2.25 0 0 0 0 4.5Z" clipRule="evenodd" /></svg>
                      </span>
                      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                        <strong className="font-semibold text-zinc-900">{outlet.name}</strong>
                        {outlet.address && <span className="text-sm text-zinc-500">{outlet.address}</span>}
                      </span>
                      <span className="text-xl text-zinc-300 transition group-hover:translate-x-0.5 group-hover:text-indigo-500" aria-hidden="true">›</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <>
            <p className={loginStyles.prompt}>
              Manager PIN · <strong>{chosen.name}</strong>
            </p>
            <PinEntry key={chosen.id} onSubmit={confirm} lockStatus={pinLockStatus.setUp(chosen.id)} />
            <button type="button" className={loginStyles.link} onClick={() => setChosen(null)}>
              Choose another outlet
            </button>
          </>
        )}
      </div>
    </main>
  )
}
