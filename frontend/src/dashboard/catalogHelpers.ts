import { ApiError } from '../api/http'

/** A reorder list that does not match the rows it was built from: it is out of date, so the screen has to reload. */
export class StaleListError extends Error {
  constructor(text: string) {
    super(text)
    this.name = 'StaleListError'
  }
}

export function message(error: unknown) {
  return error instanceof ApiError || error instanceof StaleListError ? error.message : 'Offline'
}

/** What the API answers (409) to an edit made from a copy older than the row. */
export const CHANGED_ELSEWHERE = 'Changed elsewhere. Reload.'

/** Whether an edit was refused because the row was saved by someone else since the form loaded: reload to see theirs. */
export function isChangedElsewhere(error: unknown) {
  return error instanceof ApiError && error.status === 409 && error.message === CHANGED_ELSEWHERE
}

/** A reorder made from a list that is missing a row (one was added or removed since it loaded). */
export function isIncompleteOrder(error: unknown) {
  return error instanceof ApiError && error.status === 400 && error.message.startsWith('The order must list every one of the')
}

/** What the API answers (409) when a catalogue change collides with another one or a sale, and says to reload. */
export const COLLIDED = 'That change collided with another one, or with a sale. Reload and try again.'

export function isCollision(error: unknown) {
  return error instanceof ApiError && error.status === 409 && error.message === COLLIDED
}

/** Whether the screen is out of date with the catalogue, so the next attempt has to start from a reload. */
export function needsReload(error: unknown) {
  return isChangedElsewhere(error) || isCollision(error) || isIncompleteOrder(error) || error instanceof StaleListError
}

/** The body of a reorder: the ids in the order wanted and the version each row was loaded at (a row saved since refuses it). */
export function orderBody(rows: { id: number; version: number }[], ids: number[]) {
  const versions = new Map(rows.map((row) => [row.id, row.version]))
  return {
    ids,
    versions: ids.map((id) => {
      const version = versions.get(id)
      // Never a guess: a row this screen has no version for is not in the list it was loaded with.
      if (version === undefined) throw new StaleListError('The list is out of date. Reload.')
      return version
    }),
  }
}

/** Moves `id` one place up or down in `ids`; null at either end. */
export function moved(ids: number[], id: number, by: -1 | 1): number[] | null {
  const from = ids.indexOf(id)
  const to = from + by
  if (from < 0 || to < 0 || to >= ids.length) return null
  const next = [...ids]
  ;[next[from], next[to]] = [next[to], next[from]]
  return next
}
