import { getJson, postJson } from '../api/http'
import { authPaths, type PinLockStatus } from './types'

/**
 * How long each PIN pad is still locked for, in seconds (0: not locked), for PinEntry's
 * `lockStatus`: it blocks the keypad meanwhile and frees it once a manager unlocks it.
 */
export const pinLockStatus = {
  /** The till's PIN login, before anyone has logged in. */
  login: (deviceKey: string) => () =>
    postJson<PinLockStatus>(authPaths.pinLock, { deviceKey }).then((status) => status.lockedForSeconds),
  /** Setting this iPad up at an outlet. */
  setUp: (outletId: number) => () =>
    postJson<PinLockStatus>(authPaths.pinLock, { outletId }).then((status) => status.lockedForSeconds),
  /** A logged-in till's own pads: manager approvals (TILL) or Clock In/Out (CLOCK). */
  terminal: (pad: 'TILL' | 'CLOCK') => () =>
    getJson<PinLockStatus>(authPaths.terminalPinLock(pad)).then((status) => status.lockedForSeconds),
}
