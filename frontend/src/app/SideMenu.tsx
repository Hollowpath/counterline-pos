import { useEffect } from 'react'
import type { Staff } from '../auth/types'
import { countBadge } from '../styles/ui'

export type Screen = 'pos' | 'orders' | 'register' | 'reports' | 'display'

/**
 * The till's screens, in menu order. Customer management is on the back-office dashboard; the
 * till finds or adds a customer from the cart.
 */
const ITEMS: { screen: Screen; label: string; icon: string }[] = [
  { screen: 'pos', label: 'Point of Sale', icon: 'M2.25 2.25h1.386c.51 0 .955.343 1.087.835l.383 1.437M7.5 14.25a3 3 0 0 0-3 3h15.75m-12.75-3h11.218c1.121-2.3 2.1-4.684 2.924-7.138a60.114 60.114 0 0 0-16.536-1.84M7.5 14.25 5.106 5.272M6 20.25a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Zm12.75 0a.75.75 0 1 1-1.5 0 .75.75 0 0 1 1.5 0Z' },
  { screen: 'orders', label: 'Past Orders', icon: 'M9 12h3.75M9 15h3.75M9 18h3.75m3 .75H18a2.25 2.25 0 0 0 2.25-2.25V6.108c0-1.135-.845-2.098-1.976-2.192a48.424 48.424 0 0 0-1.123-.08m-5.801 0c-.065.21-.1.433-.1.664 0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75 2.25 2.25 0 0 0-.1-.664m-5.8 0A2.251 2.251 0 0 1 13.5 2.25H15c1.012 0 1.867.668 2.15 1.586m-5.8 0c-.376.023-.75.05-1.124.08C9.095 4.01 8.25 4.973 8.25 6.108V8.25m0 0H4.875c-.621 0-1.125.504-1.125 1.125v11.25c0 .621.504 1.125 1.125 1.125h9.75c.621 0 1.125-.504 1.125-1.125V9.375c0-.621-.504-1.125-1.125-1.125H8.25ZM6.75 12h.008v.008H6.75V12Zm0 3h.008v.008H6.75V15Zm0 3h.008v.008H6.75V18Z' },
  { screen: 'register', label: 'Manage Register', icon: 'M2.25 18.75a60.07 60.07 0 0 1 15.797 2.101c.727.198 1.453-.342 1.453-1.096V18.75M3.75 4.5v.75A.75.75 0 0 1 3 6h-.75m0 0v-.375c0-.621.504-1.125 1.125-1.125H20.25M2.25 6v9m18-10.5v.75c0 .414.336.75.75.75h.75m-1.5-1.5h.375c.621 0 1.125.504 1.125 1.125v9.75c0 .621-.504 1.125-1.125 1.125h-.375m1.5-1.5H21a.75.75 0 0 0-.75.75v.75m0 0H3.75m0 0h-.375a1.125 1.125 0 0 1-1.125-1.125V15m1.5 1.5v-.75A.75.75 0 0 0 3 15h-.75M15 10.5a3 3 0 1 1-6 0 3 3 0 0 1 6 0Zm3 0h.008v.008H18V10.5Zm-12 0h.008v.008H6V10.5Z' },
  { screen: 'reports', label: 'Reports', icon: 'M3 13.125C3 12.504 3.504 12 4.125 12h2.25c.621 0 1.125.504 1.125 1.125v6.75C7.5 20.496 6.996 21 6.375 21h-2.25A1.125 1.125 0 0 1 3 19.875v-6.75ZM9.75 8.625c0-.621.504-1.125 1.125-1.125h2.25c.621 0 1.125.504 1.125 1.125v11.25c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V8.625ZM16.5 4.125c0-.621.504-1.125 1.125-1.125h2.25C20.496 3 21 3.504 21 4.125v15.75c0 .621-.504 1.125-1.125 1.125h-2.25a1.125 1.125 0 0 1-1.125-1.125V4.125Z' },
  { screen: 'display', label: 'Customer Display', icon: 'M9 17.25v1.007a3 3 0 0 1-.879 2.122L7.5 21h9l-.621-.621A3 3 0 0 1 15 18.257V17.25m6-12V15a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 15V5.25m18 0A2.25 2.25 0 0 0 18.75 3H5.25A2.25 2.25 0 0 0 3 5.25m18 0V12a2.25 2.25 0 0 1-2.25 2.25H5.25A2.25 2.25 0 0 1 3 12V5.25' },
]

interface SideMenuProps {
  staff: Staff
  outletName: string
  /** Placed, unpaid orders: a count beside Past Orders. */
  openOrders?: number
  current: Screen
  onNavigate: (screen: Screen) => void
  onClockInOut: () => void
  onClose: () => void
  onLogOff: () => void
}

/** Slide-over navigation with the outlet, the logged-in staff member, Clock In/Out and Log Off. */
export function SideMenu({ staff, outletName, openOrders = 0, current, onNavigate, onClockInOut, onClose, onLogOff }: SideMenuProps) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [onClose])

  const initials = staff.fullName.split(/\s+/).map((word) => word[0]).slice(0, 2).join('').toUpperCase()

  return (
    <div className="fixed inset-0 z-10 animate-fade-in bg-zinc-950/50 backdrop-blur-[2px]" onClick={onClose}>
      <nav
        className="flex h-full w-[min(19rem,85vw)] animate-drawer-in flex-col bg-zinc-800 text-white shadow-2xl ring-1 ring-white/10"
        aria-label="Main menu"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="flex items-center gap-3 px-5 pt-6 pb-5">
          <span className="grid size-10 place-items-center rounded-xl bg-linear-to-br from-indigo-500 to-fuchsia-500 text-lg font-black shadow-lg shadow-indigo-500/30" aria-hidden="true">
            C
          </span>
          <div className="min-w-0">
            <p className="text-[0.7rem] font-semibold tracking-widest text-zinc-500 uppercase">Outlet</p>
            <p className="truncate text-sm font-semibold text-zinc-100">{outletName}</p>
          </div>
        </div>
        <ul className="flex flex-1 list-none flex-col gap-1 overflow-y-auto px-3 py-2">
          {ITEMS.map((item) => (
            <li key={item.screen}>
              <button
                type="button"
                className={[
                  'flex w-full items-center gap-3 rounded-xl px-3 py-3 text-left text-[0.95rem] font-medium text-zinc-300 transition',
                  'hover:bg-white/5 hover:text-white aria-[current=page]:bg-zinc-950/70 aria-[current=page]:font-semibold aria-[current=page]:text-white',
                  'aria-[current=page]:shadow-[inset_3px_0_0_var(--color-indigo-500)]',
                ].join(' ')}
                aria-current={item.screen === current ? 'page' : undefined}
                onClick={() => onNavigate(item.screen)}
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.6} className="size-5 shrink-0 opacity-80" aria-hidden="true">
                  <path strokeLinecap="round" strokeLinejoin="round" d={item.icon} />
                </svg>
                {item.label}
                {item.screen === 'orders' && openOrders > 0 && (
                  <span key={openOrders} className={`${countBadge} ml-auto`} aria-label={`${openOrders} open`}>{openOrders}</span>
                )}
              </button>
            </li>
          ))}
        </ul>

        <div className="m-3 rounded-2xl bg-white/5 p-3 ring-1 ring-white/10">
          <div className="flex items-center gap-3">
            <span className="grid size-10 shrink-0 place-items-center rounded-full bg-zinc-700 text-sm font-bold text-zinc-100" aria-hidden="true">
              {initials}
            </span>
            <p className="flex min-w-0 flex-1 flex-col text-sm font-semibold">
              <span className="truncate">{staff.fullName}</span>
              <span className="text-xs font-normal text-zinc-400">{staff.role === 'MANAGER' ? 'Manager' : 'Staff'}</span>
            </p>
            <button
              type="button"
              className="grid size-10 shrink-0 place-items-center rounded-xl bg-white/10 text-zinc-200 transition hover:bg-white/20 hover:text-white"
              aria-label="Clock In/Out"
              onClick={onClockInOut}
            >
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8} className="size-5" aria-hidden="true">
                <path strokeLinecap="round" strokeLinejoin="round" d="M12 6v6h4.5m4.5 0a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z" />
              </svg>
            </button>
          </div>
          <button
            type="button"
            className="mt-3 w-full rounded-xl bg-red-600 py-3 text-sm font-semibold text-white shadow-lg shadow-red-950/30 transition hover:bg-red-500"
            onClick={onLogOff}
          >
            Log Off
          </button>
        </div>
      </nav>
    </div>
  )
}
