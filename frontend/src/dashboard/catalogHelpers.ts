import { ApiError } from '../api/http'

export function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'Offline'
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
