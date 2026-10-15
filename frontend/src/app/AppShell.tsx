import { useCallback, useEffect, useMemo, useState } from 'react'
import { getJson } from '../api/http'
import type { TerminalOutlet } from '../auth/terminalStore'
import type { Staff } from '../auth/types'
import { PastOrdersScreen } from '../features/orders/PastOrdersScreen'
import { RegisterContext, registerPath, type RegisterStatus } from '../features/register/registerApi'
import { RegisterScreen } from '../features/register/RegisterScreen'
import { ReportsScreen } from '../features/reports/ReportsScreen'
import { SellScreen } from '../features/sell/SellScreen'
import { ClockDialog } from '../features/timecards/ClockDialog'
import { CustomerDisplayScreen } from '../features/display/CustomerDisplayScreen'
import { loadDisplayId } from '../features/display/displayApi'
import { SideMenu, type Screen } from './SideMenu'

const WELCOME_TOAST_MS = 2500

interface AppShellProps {
  staff: Staff
  /** The terminal's outlet: everything recorded here belongs to it. */
  outlet: TerminalOutlet
  welcome: boolean
  onLogOff: () => void
}

export function AppShell({ staff, outlet, welcome, onLogOff }: AppShellProps) {
  const [screen, setScreen] = useState<Screen>('pos')
  const [menuOpen, setMenuOpen] = useState(false)
  const [clocking, setClocking] = useState(false)
  const [showWelcome, setShowWelcome] = useState(welcome)
  const [registerOpen, setRegisterOpen] = useState<boolean | null>(null)
  const [openOrders, setOpenOrders] = useState(0)
  const [saleOpen, setSaleOpen] = useState(false)
  // The customer display paired with this till, if any.
  const [displayId, setDisplayId] = useState<number | null>(loadDisplayId)
  // "Edit Order" from Past Orders: the order to load into the cart (seq: each request is new).
  const [editRequest, setEditRequest] = useState<{ orderId: number; seq: number } | null>(null)
  // An unpaid order voided in Past Orders: the Point of Sale lets go of it (seq: each void is new).
  const [voidedOrder, setVoidedOrder] = useState<{ orderId: number; seq: number } | null>(null)
  const forgetDisplay = useCallback(() => setDisplayId(null), [])
  const openMenu = () => setMenuOpen(true)

  // Whether payment can be taken; re-checked on every screen change (another terminal may have
  // opened or closed the register). The server enforces it either way.
  const refreshRegister = useCallback(() => {
    getJson<RegisterStatus>(registerPath)
      .then((status) => {
        setRegisterOpen(status.open)
        setOpenOrders(status.openOrders)
      })
      .catch(() => {
        setRegisterOpen(null)
        setOpenOrders(0)
      })
  }, [])
  useEffect(refreshRegister, [refreshRegister])
  const register = useMemo(
    () => ({ open: registerOpen, openOrders, saleOpen, refresh: refreshRegister }),
    [registerOpen, openOrders, saleOpen, refreshRegister],
  )

  useEffect(() => {
    if (!showWelcome) return
    const timer = window.setTimeout(() => setShowWelcome(false), WELCOME_TOAST_MS)
    return () => window.clearTimeout(timer)
  }, [showWelcome])

  return (
    <RegisterContext.Provider value={register}>
      {/* Kept mounted so a sale in progress survives a visit to another screen. */}
      <div hidden={screen !== 'pos'}>
        <SellScreen
          onOpenMenu={openMenu}
          isManager={staff.role === 'MANAGER'}
          displayId={displayId}
          onDisplayGone={forgetDisplay}
          editRequest={editRequest}
          voidedOrder={voidedOrder}
          onSaleOpenChange={setSaleOpen}
          // Every paid sale ends with the staff member logged off, for the next one to log in.
          onSaleComplete={onLogOff}
        />
      </div>
      {screen === 'orders' && (
        <PastOrdersScreen
          staff={staff}
          onOpenMenu={openMenu}
          onEditOrder={(orderId) => {
            setEditRequest((previous) => ({ orderId, seq: (previous?.seq ?? 0) + 1 }))
            setScreen('pos')
            refreshRegister()
          }}
          onOrderVoided={(orderId) => {
            setVoidedOrder((previous) => ({ orderId, seq: (previous?.seq ?? 0) + 1 }))
            refreshRegister()
          }}
        />
      )}
      {screen === 'register' && <RegisterScreen onOpenMenu={openMenu} />}
      {screen === 'reports' && <ReportsScreen outletName={outlet.name} onOpenMenu={openMenu} />}
      {screen === 'display' && <CustomerDisplayScreen displayId={displayId} onDisplayChange={setDisplayId} onOpenMenu={openMenu} />}

      {menuOpen && (
        <SideMenu
          staff={staff}
          outletName={outlet.name}
          openOrders={openOrders}
          current={screen}
          onNavigate={(next) => {
            setScreen(next)
            setMenuOpen(false)
            refreshRegister()
          }}
          onClockInOut={() => {
            setMenuOpen(false)
            setClocking(true)
          }}
          onClose={() => setMenuOpen(false)}
          onLogOff={onLogOff}
        />
      )}
      {clocking && <ClockDialog onClose={() => setClocking(false)} />}
      {showWelcome && (
        <div className="pointer-events-none fixed top-[40%] left-1/2 z-20 flex -translate-x-1/2 items-center gap-3 rounded-2xl bg-zinc-900/90 px-6 py-4 font-semibold text-white shadow-2xl ring-1 ring-white/10 backdrop-blur" role="status">
          <span className="grid size-8 place-items-center rounded-full bg-green-500 text-sm" aria-hidden="true">✓</span>
          Welcome, {staff.fullName}
        </div>
      )}
    </RegisterContext.Provider>
  )
}
