const dateTime = new Intl.DateTimeFormat('en-MY', { dateStyle: 'medium', timeStyle: 'short' })
const time = new Intl.DateTimeFormat('en-MY', { timeStyle: 'short' })
const date = new Intl.DateTimeFormat('en-MY', { dateStyle: 'medium' })

/** Formatters for a named time zone (the business's), made once each. */
const inZone = new Map<string, { dateTime: Intl.DateTimeFormat; time: Intl.DateTimeFormat }>()

function formattersIn(zone: string) {
  let formatters = inZone.get(zone)
  if (!formatters) {
    formatters = {
      dateTime: new Intl.DateTimeFormat('en-MY', { dateStyle: 'medium', timeStyle: 'short', timeZone: zone }),
      time: new Intl.DateTimeFormat('en-MY', { timeStyle: 'short', timeZone: zone }),
    }
    inZone.set(zone, formatters)
  }
  return formatters
}

/** Shown in the terminal's local time zone, which is the outlet's; or in {@code zone}, the business time zone, when given. */
export function formatDateTime(iso: string, zone?: string): string {
  return (zone ? formattersIn(zone).dateTime : dateTime).format(new Date(iso))
}

export function formatTime(iso: string, zone?: string): string {
  return (zone ? formattersIn(zone).time : time).format(new Date(iso))
}

/**
 * The wall-clock time now, in {@code zone} (the business time zone) as a datetime-local value (YYYY-MM-DDTHH:mm). Without a
 * zone, the browser's. The value carries no zone: whoever reads it reads it in the business's.
 */
export function localDateTimeIn(zone?: string, now: Date = new Date()): string {
  if (!zone) {
    const local = new Date(now.getTime() - now.getTimezoneOffset() * 60_000)
    return local.toISOString().slice(0, 16)
  }
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', {
      timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
    }).formatToParts(now).map((part) => [part.type, part.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}`
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
