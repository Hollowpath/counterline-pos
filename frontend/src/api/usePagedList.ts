import { useCallback, useEffect, useState } from 'react'
import { getJson } from './http'

/** Mirrors backend common.PageResponse. */
export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export type PagedListState<T> =
  | { phase: 'loading' }
  | { phase: 'error'; message: string }
  | { phase: 'ready'; items: T[]; page: number; totalPages: number; loadingMore: boolean }

/**
 * A paged API list: loads the first page whenever the query changes (stale responses are
 * discarded) and appends further pages on demand.
 */
/** {@code enabled} false: nothing is asked for, and the list is empty (a search that has not been typed yet). */
export function usePagedList<T>(pathForPage: (page: number) => string, enabled = true) {
  const [state, setState] = useState<PagedListState<T>>({ phase: 'loading' })
  const [refreshKey, setRefreshKey] = useState(0)
  const firstPagePath = pathForPage(0)

  useEffect(() => {
    if (!enabled) {
      setState({ phase: 'ready', items: [], page: 0, totalPages: 0, loadingMore: false })
      return
    }
    const controller = new AbortController()
    setState({ phase: 'loading' })
    getJson<Page<T>>(firstPagePath, controller.signal)
      .then((page) =>
        setState({ phase: 'ready', items: page.items, page: 0, totalPages: page.totalPages, loadingMore: false }),
      )
      .catch((error: unknown) => {
        if (controller.signal.aborted) return
        setState({ phase: 'error', message: error instanceof Error ? error.message : 'Could not load the list' })
      })
    return () => controller.abort()
  }, [firstPagePath, refreshKey, enabled])

  const refresh = useCallback(() => setRefreshKey((key) => key + 1), [])

  async function loadMore() {
    if (state.phase !== 'ready' || state.loadingMore) return
    const nextPage = state.page + 1
    setState({ ...state, loadingMore: true })
    try {
      const page = await getJson<Page<T>>(pathForPage(nextPage))
      setState((current) =>
        current.phase === 'ready'
          ? { ...current, items: [...current.items, ...page.items], page: nextPage, totalPages: page.totalPages, loadingMore: false }
          : current,
      )
    } catch {
      setState((current) => (current.phase === 'ready' ? { ...current, loadingMore: false } : current))
    }
  }

  const hasMore = state.phase === 'ready' && state.page + 1 < state.totalPages
  return { state, hasMore, loadMore, refresh }
}
