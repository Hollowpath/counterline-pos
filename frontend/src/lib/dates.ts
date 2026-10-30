const dateTime = new Intl.DateTimeFormat('en-MY', { dateStyle: 'medium', timeStyle: 'short' })
const time = new Intl.DateTimeFormat('en-MY', { timeStyle: 'short' })
const date = new Intl.DateTimeFormat('en-MY', { dateStyle: 'medium' })

/** Shown in the terminal's local time zone, which is the outlet's. */
export function formatDateTime(iso: string): string {
  return dateTime.format(new Date(iso))
}

export function formatTime(iso: string): string {
  return time.format(new Date(iso))
}

export function formatDate(iso: string): string {
  return date.format(new Date(iso))
}

/** Today's date (terminal-local, i.e. the outlet's) as YYYY-MM-DD. */
export function todayIso(now: Date = new Date()): string {
  const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 10)
}

/** What a birth date in the future is told. */
export const FUTURE_BIRTH_DATE = "Date of birth can't be in the future"

/** Whether a YYYY-MM-DD date is after today (terminal-local). Today itself is not. */
export function isFutureDate(isoDate: string, now: Date = new Date()): boolean {
  return isoDate > todayIso(now)
}

/** Shifts a YYYY-MM-DD date by whole days (calendar arithmetic, no time zone involved). */
export function addDays(isoDate: string, days: number): string {
  const shifted = new Date(`${isoDate}T00:00:00Z`)
  shifted.setUTCDate(shifted.getUTCDate() + days)
  return shifted.toISOString().slice(0, 10)
}

/** Inclusive number of days from `from` to `to`. */
export function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000) + 1
}
