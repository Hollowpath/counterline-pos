import { AppShell } from './app/AppShell'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { LoginScreen } from './auth/LoginScreen'
import { TerminalSetupScreen } from './auth/TerminalSetupScreen'
import { LoadState } from './components/LoadState'

function AuthGate() {
  const { state, terminal, setUpNotice, logout } = useAuth()

  switch (state.status) {
    case 'checking':
      return <LoadState status="loading" />
    case 'anonymous':
      // A new (or reset) iPad is set up for an outlet first; staff then log in there. Only a
      // reset from the dashboard moves it to another outlet.
      if (terminal === null) return <TerminalSetupScreen notice={setUpNotice ?? undefined} />
      return <LoginScreen outlet={terminal} notice={state.notice} />
    case 'authenticated':
      return <AppShell staff={state.staff} outlet={state.outlet} welcome={state.welcome} onLogOff={() => logout()} />
  }
}

export default function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  )
}
