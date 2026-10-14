/**
 * The outlet this iPad is set up for, once, by a manager on the set-up screen. `deviceKey` is the
 * iPad's own key from the server: it is sent with every staff login, and stops working when the
 * iPad is reset from the dashboard.
 */
export interface TerminalOutlet {
  id: number
  name: string
}

export interface TerminalBinding extends TerminalOutlet {
  deviceKey: string
  /** "iPad 1", as the dashboard lists it. */
  label: string
}

const KEY = 'counterline.terminal'

/*
 * localStorage: the binding outlives closing the app (unlike the session). If storage is
 * unavailable (private mode, blocked site data), the set-up screen simply appears again. A binding
 * saved before iPads had keys is ignored, so the iPad is set up again.
 */
export function loadTerminal(): TerminalBinding | null {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return null
    const binding = JSON.parse(raw) as TerminalBinding
    return typeof binding.id === 'number' && typeof binding.name === 'string' && typeof binding.deviceKey === 'string'
      ? binding
      : null
  } catch {
    return null
  }
}

export function saveTerminal(binding: TerminalBinding) {
  try {
    localStorage.setItem(KEY, JSON.stringify(binding))
  } catch {
    // The terminal will ask to be set up again next time.
  }
}

/** The iPad was reset from the dashboard: it has to be set up again. */
export function forgetTerminal() {
  try {
    localStorage.removeItem(KEY)
  } catch {
    // Nothing stored.
  }
}
