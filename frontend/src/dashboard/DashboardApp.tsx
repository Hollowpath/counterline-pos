import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { ApiError, postJson, setAuthToken, setUnauthorizedHandler } from '../api/http'
import { AppBar } from '../app/AppBar'
import { CustomersScreen } from '../features/customers/CustomersScreen'
import { DashboardReports } from './DashboardReports'
import { DiscountsAdmin } from './DiscountsAdmin'
import { ItemsAdmin } from './ItemsAdmin'
import { LiveSales } from './LiveSales'
import { MemberRewardsPanel } from './MemberRewardsPanel'
import { SalesOverview } from './SalesOverview'
import { field } from './styles'
import { TillDevicesAdmin } from './TillDevicesAdmin'
import { dashboardPaths, type DashboardSession } from './types'
import { Page } from './ui'
import { VouchersAdmin } from './VouchersAdmin'
import { appBarTitle, loginStyles } from '../styles/ui'

const KEY = 'counterline.dashboard'

function loadSession(now: Date = new Date()): DashboardSession | null {
  try {
    const raw = sessionStorage.getItem(KEY)
    const session = raw ? (JSON.parse(raw) as DashboardSession) : null
    return session && new Date(session.expiresAt) > now ? session : null
  } catch {
    return null
  }
}

function saveSession(session: DashboardSession | null) {
  try {
    if (session) sessionStorage.setItem(KEY, JSON.stringify(session))
    else sessionStorage.removeItem(KEY)
  } catch {
    // The session just won't survive a reload.
  }
}

type PageId = 'overview' | 'live' | 'reports' | 'items' | 'discounts' | 'vouchers' | 'customers' | 'ipads'

/** Each page with its menu icon (an SVG path, 24×24 outline). */
const PAGES: { page: PageId; label: string; icon: string; adminOnly?: boolean }[] = [
  { page: 'overview', label: 'Overview', icon: 'M3.75 6A2.25 2.25 0 0 1 6 3.75h2.25A2.25 2.25 0 0 1 10.5 6v2.25a2.25 2.25 0 0 1-2.25 2.25H6a2.25 2.25 0 0 1-2.25-2.25V6ZM3.75 15.75A2.25 2.25 0 0 1 6 13.5h2.25a2.25 2.25 0 0 1 2.25 2.25V18a2.25 2.25 0 0 1-2.25 2.25H6A2.25 2.25 0 0 1 3.75 18v-2.25ZM13.5 6a2.25 2.25 0 0 1 2.25-2.25H18A2.25 2.25 0 0 1 20.25 6v2.25A2.25 2.25 0 0 1 18 10.5h-2.25a2.25 2.25 0 0 1-2.25-2.25V6ZM13.5 15.75a2.25 2.25 0 0 1 2.25-2.25H18a2.25 2.25 0 0 1 2.25 2.25V18A2.25 2.25 0 0 1 18 20.25h-2.25A2.25 2.25 0 0 1 13.5 18v-2.25Z' },
  { page: 'live', label: 'Live Sales', icon: 'm3.75 13.5 10.5-11.25L12 10.5h8.25L9.75 21.75 12 13.5H3.75Z' },
  { page: 'reports', label: 'Reports', icon: 'M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 0 1 3 19.875v-6.75ZM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V8.625ZM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V4.125Z' },
  { page: 'items', label: 'Items & Prices', adminOnly: true, icon: 'M9.568 3H5.25A2.25 2.25 0 0 0 3 5.25v4.318c0 .597.237 1.17.659 1.591l9.581 9.581c.699.699 1.78.872 2.607.33a18.095 18.095 0 0 0 5.223-5.223c.542-.827.369-1.908-.33-2.607L11.16 3.66A2.25 2.25 0 0 0 9.568 3ZM6 6h.008v.008H6V6Z' },
  { page: 'discounts', label: 'Discounts', adminOnly: true, icon: 'm9 15 6-6m-5.25.75h.008v.008H9.75V9.75Zm4.5 4.5h.008v.008h-.008v-.008ZM21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z' },
  { page: 'vouchers', label: 'Vouchers', adminOnly: true, icon: 'M16.5 6v.75m0 3v.75m0 3v.75m0 3V18m-9-5.25h5.25M7.5 15h3M3.375 5.25c-.621 0-1.125.504-1.125 1.125v3.026a2.999 2.999 0 0 1 0 5.198v3.026c0 .621.504 1.125 1.125 1.125h17.25c.621 0 1.125-.504 1.125-1.125v-3.026a2.999 2.999 0 0 1 0-5.198V6.375c0-.621-.504-1.125-1.125-1.125H3.375Z' },
  { page: 'customers', label: 'Customers', icon: 'M15 19.128a9.38 9.38 0 0 0 2.625.372 9.337 9.337 0 0 0 4.121-.952 4.125 4.125 0 0 0-7.533-2.493M15 19.128v-.003c0-1.113-.285-2.16-.786-3.07M15 19.128v.106A12.318 12.318 0 0 1 8.624 21c-2.331 0-4.512-.645-6.374-1.766l-.001-.109a6.375 6.375 0 0 1 11.964-3.07M12 6.375a3.375 3.375 0 1 1-6.75 0 3.375 3.375 0 0 1 6.75 0Zm8.25 2.25a2.625 2.625 0 1 1-5.25 0 2.625 2.625 0 0 1 5.25 0Z' },
  { page: 'ipads', label: 'iPads', icon: 'M10.5 19.5h3m-6.75 2.25h10.5a2.25 2.25 0 0 0 2.25-2.25v-15a2.25 2.25 0 0 0-2.25-2.25H6.75A2.25 2.25 0 0 0 4.5 4.5v15a2.25 2.25 0 0 0 2.25 2.25Z' },
]

/**
 * The back-office dashboard, on any device (phone, tablet or computer), in the POS's look so the
 * staff know their way around. Everyone logs in with email and password: head office sees every
 * outlet and edits the catalogue; an outlet manager sees their own outlet's sales and the customers.
 */
export function DashboardApp() {
  const [session, setSession] = useState<DashboardSession | null>(() => {
    const stored = loadSession()
    setAuthToken(stored?.token ?? null)
    return stored
  })
  const [notice, setNotice] = useState<string | undefined>(undefined)

  const logout = useCallback((message?: string) => {
    setAuthToken(null)
    saveSession(null)
    setSession(null)
    setNotice(message)
  }, [])

  useEffect(() => {
    setUnauthorizedHandler(() => logout('Your session has ended. Please log in again.'))
    return () => setUnauthorizedHandler(null)
  }, [logout])

  function loggedIn(next: DashboardSession) {
    setAuthToken(next.token)
    saveSession(next)
    setSession(next)
    setNotice(undefined)
  }

  return session === null
    ? <DashboardLogin notice={notice} onLoggedIn={loggedIn} />
    : <DashboardShell session={session} onLogOut={() => logout()} />
}

function DashboardShell({ session, onLogOut }: { session: DashboardSession; onLogOut: () => void }) {
  const [page, setPage] = useState<PageId>('overview')
  const [menuOpen, setMenuOpen] = useState(false)
  const admin = session.role === 'ADMIN'
  const pages = PAGES.filter((entry) => admin || !entry.adminOnly)
  const title = pages.find((entry) => entry.page === page)?.label ?? ''

  return (
    <div className="flex h-dvh max-lg:flex-col">
      {/* The POS's side menu: always beside the page on a wide screen, opened from the bar on a small one. */}
      {menuOpen && <div className="fixed inset-0 z-10 animate-fade-in bg-zinc-950/50 backdrop-blur-[2px] lg:hidden" onClick={() => setMenuOpen(false)} />}
      <nav
        aria-label="Dashboard"
        className={[
          'flex w-64 shrink-0 flex-col bg-zinc-800 text-white shadow-xl print:hidden',
          'max-lg:fixed max-lg:inset-y-0 max-lg:left-0 max-lg:z-20 max-lg:w-[min(18rem,85vw)] max-lg:transition-transform max-lg:duration-300 max-lg:ease-(--ease-spring)',
          menuOpen ? '' : 'max-lg:-translate-x-full',
        ].join(' ')}
      >
        <div className="flex items-center gap-3 px-5 pt-6 pb-5">
          <span className="grid size-10 place-items-center rounded-xl bg-linear-to-br from-indigo-500 to-fuchsia-500 text-lg font-black shadow-lg shadow-indigo-500/30" aria-hidden="true">
            C
          </span>
          <p className="flex flex-col text-sm font-semibold text-zinc-100">
            Counterline
            <span className="text-[0.7rem] font-semibold tracking-widest text-zinc-500 uppercase">Dashboard</span>
          </p>
        </div>
        <ul className="flex flex-1 list-none flex-col gap-1 overflow-y-auto px-3 py-2">
          {pages.map((entry) => (
            <li key={entry.page}>
              <button
                type="button"
                className={[
                  'flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left text-sm font-medium text-zinc-300 transition',
                  'hover:bg-white/5 hover:text-white aria-[current=page]:bg-zinc-950/70 aria-[current=page]:font-semibold aria-[current=page]:text-white',
                  'aria-[current=page]:shadow-[inset_3px_0_0_var(--color-indigo-500)]',
                ].join(' ')}
                aria-current={entry.page === page ? 'page' : undefined}
                onClick={() => {
                  setPage(entry.page)
                  setMenuOpen(false)
                }}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} className="size-5 shrink-0 opacity-80" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" d={entry.icon} />
                </svg>
                {entry.label}
              </button>
            </li>
          ))}
        </ul>
        <div className="m-3 rounded-2xl bg-white/5 p-3 ring-1 ring-white/10">
          <p className="flex flex-col text-sm font-semibold">
            {session.name}
            <span className="text-xs text-zinc-400">{admin ? 'Head office · all outlets' : `Manager · ${session.outlet?.name}`}</span>
          </p>
          <button type="button" className="mt-3 w-full rounded-xl bg-red-600 py-2.5 text-sm font-semibold text-white shadow-lg shadow-red-950/30 transition hover:bg-red-500" onClick={onLogOut}>
            Log Off
          </button>
        </div>
      </nav>
      <main className="min-h-0 min-w-0 flex-1 overflow-y-auto bg-zinc-100">
        {page === 'overview' && <SalesOverview />}
        {page === 'live' && <LiveSales />}
        {page === 'reports' && <DashboardReports />}
        {page === 'items' && admin && <ItemsAdmin />}
        {page === 'discounts' && admin && <DiscountsAdmin />}
        {page === 'vouchers' && admin && <VouchersAdmin />}
        {page === 'customers' && (
          <Page title="Customers" description="Find a member: their details, orders, points and free items owed." flush>
            <div className="min-h-0 flex-1 [&>*]:h-full!">
              <CustomersScreen renderExtra={(customerId) => <MemberRewardsPanel customerId={customerId} />} />
            </div>
          </Page>
        )}
        {page === 'ipads' && <TillDevicesAdmin />}
      </main>
      {/* On a phone or tablet: the POS's bar, at the bottom as on the till. */}
      <div className="lg:hidden print:hidden">
        <AppBar label="Dashboard menu" onOpenMenu={() => setMenuOpen(true)}>
          <p className={appBarTitle}>{title}</p>
        </AppBar>
      </div>
    </div>
  )
}

/** The till's login screen, with email and password instead of the PIN pad. */
function DashboardLogin({ notice, onLoggedIn }: { notice?: string; onLoggedIn: (session: DashboardSession) => void }) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  async function login(event: FormEvent) {
    event.preventDefault()
    setSending(true)
    setError(null)
    try {
      onLoggedIn(await postJson<DashboardSession>(dashboardPaths.login, { email, password }))
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Offline')
      setSending(false)
    }
  }

  const label = 'flex flex-col gap-1.5 text-sm font-medium text-zinc-700'
  return (
    <main className={loginStyles.screen}>
      <div className={loginStyles.panel}>
        <h1 className={loginStyles.title}>Counterline Dashboard</h1>
        <p className="mt-1 mb-6 text-center text-sm text-zinc-500">Head office and outlet managers: log in with your email.</p>
        {notice && (
          <p className={`${loginStyles.notice} mt-0! mb-4`} role="status">{notice}</p>
        )}
        <form className="flex w-full flex-col gap-3" onSubmit={(event) => void login(event)}>
          <label className={label}>
            Email
            <input className={field} type="email" autoComplete="username" required value={email}
              onChange={(event) => setEmail(event.target.value)} />
          </label>
          <label className={label}>
            Password
            <input className={field} type="password" autoComplete="current-password" required value={password}
              onChange={(event) => setPassword(event.target.value)} />
          </label>
          {error && <p className="rounded-xl bg-red-50 px-3 py-2 text-sm font-medium text-red-700 ring-1 ring-red-200 ring-inset" role="alert">{error}</p>}
          <button type="submit" className="btn btn-primary mt-2 py-3.5!" disabled={sending}>
            {sending ? 'Logging in…' : 'Log in'}
          </button>
        </form>
      </div>
    </main>
  )
}
