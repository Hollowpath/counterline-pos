import { flushSync } from 'react-dom'
import { createRoot, type Root } from 'react-dom/client'
import { getJson } from '../../api/http'
import { loadTerminal } from '../../auth/terminalStore'
import { authPaths, type Outlet } from '../../auth/types'
import { checkoutPaths, type Order } from '../checkout/types'
import { Receipt } from './Receipt'

type ReceiptOutlet = Pick<Outlet, 'name' | 'address'>

/** The slip being printed. It lives on the page, outside every screen, until the next print replaces it. */
let slip: Root | null = null

/**
 * The iPad's outlet: every order it can open was sold there. Its address is kept with the iPad's
 * set-up; only one set up before that is looked up (and without it the slip prints the name).
 */
async function receiptOutlet(): Promise<ReceiptOutlet | null> {
  const terminal = loadTerminal()
  if (!terminal) return null
  if (terminal.address !== undefined) return { name: terminal.name, address: terminal.address }
  const outlet = await getJson<Outlet>(authPaths.outlet(terminal.id)).catch(() => null)
  return outlet ?? { name: terminal.name, address: null }
}

/**
 * Prints the order's customer receipt and opens the iPad's print dialog. Only the slip is printed
 * (see `#receipt-print` in index.css). The slip is never taken away early: an iPad's print sheet
 * opens without waiting and browsers differ on when they report printing is over, so the screen
 * that asked for it may close while it is still printing.
 */
export async function printReceipt(order: Order): Promise<void> {
  const outlet = await receiptOutlet()
  if (!slip) {
    const container = document.createElement('div')
    container.id = 'receipt-print'
    container.className = 'hidden print:block'
    document.body.append(container)
    slip = createRoot(container)
  }
  const root = slip
  flushSync(() => root.render(<Receipt order={order} outlet={outlet} />))
  window.requestAnimationFrame(() => window.print())
}

/**
 * Prints the screen itself (a report, the register's summary). The last receipt's slip goes first:
 * while it is on the page, printing shows only the slip.
 */
export function printScreen(): void {
  slip?.unmount()
  slip = null
  document.getElementById('receipt-print')?.remove()
  window.print()
}

/** Prints the order as the server has it now (its status may have changed since it was shown). */
export async function printOrderReceipt(orderId: number): Promise<void> {
  await printReceipt(await getJson<Order>(checkoutPaths.order(orderId)))
}
