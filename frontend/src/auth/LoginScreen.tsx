import { ApiError, pinLockout } from '../api/http'
import { useAuth } from './AuthContext'
import { PinEntry } from './PinEntry'
import { pinLockStatus } from './pinLock'
import type { TerminalOutlet } from './terminalStore'
import { loginStyles } from '../styles/ui'

function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiError && (error.status === 401 || error.status === 429)) return error.message
  if (error instanceof ApiError && error.status === 404) return 'Outlet closed'
  return 'Offline'
}

interface LoginScreenProps {
  outlet: TerminalOutlet
  notice?: string
}

/**
 * Staff PIN login at the iPad's outlet: submits automatically once the sixth digit is entered. The
 * outlet can't be changed here; head office resets the iPad from the dashboard instead.
 */
export function LoginScreen({ outlet, notice }: LoginScreenProps) {
  const { login, terminal } = useAuth()

  async function submit(pin: string) {
    try {
      await login(pin)
      // On success this screen unmounts (and on a reset iPad, the set-up screen takes over).
    } catch (error) {
      return pinLockout(error) ?? loginErrorMessage(error)
    }
  }

  return (
    <main className={loginStyles.screen}>
      <div className={loginStyles.panel}>
        <h1 className={loginStyles.title}>{outlet.name}</h1>
        <p className={loginStyles.prompt}>Enter your staff PIN</p>
        <PinEntry onSubmit={submit} notice={notice} lockStatus={terminal ? pinLockStatus.login(terminal.deviceKey) : undefined} />
      </div>
    </main>
  )
}
