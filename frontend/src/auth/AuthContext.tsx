import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { ApiError, getJson, postJson, setAuthToken, setUnauthorizedHandler } from '../api/http'
import { clearSession, loadSession, saveSession } from './sessionStore'
import { forgetTerminal, loadTerminal, saveTerminal, type TerminalBinding, type TerminalOutlet } from './terminalStore'
import { authPaths, type LoginResponse, type Outlet, type Staff, type TerminalSetupResponse } from './types'

export type AuthState =
  | { status: 'checking' }
  | { status: 'anonymous'; notice?: string }
  /** `welcome` is true right after an interactive login (not after restoring a session). */
  | { status: 'authenticated'; staff: Staff; outlet: TerminalOutlet; welcome: boolean }

interface AuthContextValue {
  state: AuthState
  /** The outlet this iPad is set up for; null until a manager sets it up. */
  terminal: TerminalBinding | null
  /** Why the iPad is on the set-up screen again (it was reset from the dashboard). */
  setUpNotice: string | null
  /** Sets the iPad up for an outlet, confirmed by that outlet's manager's PIN. */
  setUpTerminal: (pin: string, outlet: Outlet) => Promise<void>
  login: (pin: string) => Promise<void>
  logout: (notice?: string) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export const SESSION_ENDED_NOTICE = 'Session ended'

export function AuthProvider({ children }: { children: ReactNode }) {
  const [restored] = useState(() => {
    const stored = loadSession()
    setAuthToken(stored?.token ?? null)
    return stored
  })
  const [state, setState] = useState<AuthState>(restored ? { status: 'checking' } : { status: 'anonymous' })
  const [terminal, setTerminal] = useState<TerminalBinding | null>(loadTerminal)
  const [setUpNotice, setSetUpNotice] = useState<string | null>(null)

  const logout = useCallback((notice?: string) => {
    setAuthToken(null)
    clearSession()
    setState({ status: 'anonymous', notice })
  }, [])

  // Any authenticated request answered with 401 ends the session.
  useEffect(() => {
    setUnauthorizedHandler(() => logout(SESSION_ENDED_NOTICE))
    return () => setUnauthorizedHandler(null)
  }, [logout])

  // A stored token is confirmed with the server (the staff member may have been deactivated).
  // A 401 ends the session via the unauthorized handler; if the server is merely unreachable,
  // carry on with the stored details — every later request is still checked server-side.
  useEffect(() => {
    if (state.status !== 'checking' || !restored) return
    getJson<Staff>(authPaths.me)
      .then((staff) => setState({ status: 'authenticated', staff, outlet: restored.outlet, welcome: false }))
      .catch((error: unknown) => {
        if (error instanceof ApiError && error.status === 401) return
        setState({ status: 'authenticated', staff: restored.staff, outlet: restored.outlet, welcome: false })
      })
  }, [state.status, restored])

  const setUpTerminal = useCallback(async (pin: string, outlet: Outlet) => {
    const response = await postJson<TerminalSetupResponse>(authPaths.terminalSetup, { pin, outletId: outlet.id })
    const bound = {
      id: response.outlet.id, name: response.outlet.name, deviceKey: response.deviceKey, label: response.label,
      address: response.outlet.address,
    }
    saveTerminal(bound)
    setSetUpNotice(null)
    setTerminal(bound)
  }, [])

  const login = useCallback(async (pin: string) => {
    if (!terminal) throw new Error('This terminal is not set up')
    let response: LoginResponse
    try {
      response = await postJson<LoginResponse>(authPaths.login, { pin, deviceKey: terminal.deviceKey })
    } catch (error) {
      // Reset from the dashboard: back to the set-up screen.
      if (error instanceof ApiError && error.status === 410) {
        forgetTerminal()
        setSetUpNotice(error.message)
        setTerminal(null)
      }
      throw error
    }
    const outlet = { id: response.outlet.id, name: response.outlet.name }
    // The outlet's details as they are now (a binding from before the address was kept gets it here).
    if (terminal.address !== response.outlet.address || terminal.name !== response.outlet.name) {
      const updated = { ...terminal, name: response.outlet.name, address: response.outlet.address }
      saveTerminal(updated)
      setTerminal(updated)
    }
    setAuthToken(response.token)
    saveSession({ token: response.token, expiresAt: response.expiresAt, staff: response.staff, outlet })
    setState({ status: 'authenticated', staff: response.staff, outlet, welcome: true })
  }, [terminal])

  const value = useMemo(
    () => ({ state, terminal, setUpNotice, setUpTerminal, login, logout }),
    [state, terminal, setUpNotice, setUpTerminal, login, logout],
  )
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components -- hook belongs with its provider
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used inside AuthProvider')
  return context
}
