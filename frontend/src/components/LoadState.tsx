interface LoadStateProps {
  status: 'loading' | 'error'
  message?: string
  onRetry?: () => void
}

/** Loading indicator or error message with a retry action, for any data-backed panel. */
export function LoadState({ status, message, onRetry }: LoadStateProps) {
  if (status === 'loading') {
    return (
      <div className="flex items-center justify-center gap-3 p-10 text-sm font-medium text-zinc-500" role="status">
        <span className="size-5 animate-spin rounded-full border-2 border-zinc-200 border-t-indigo-600" aria-hidden="true" />
        Loading…
      </div>
    )
  }
  return (
    <div className="flex flex-col items-center gap-3 p-10 text-center text-sm font-medium text-red-600" role="alert">
      <p>{message ?? 'Something went wrong.'}</p>
      {onRetry && (
        <button type="button" className="btn" onClick={onRetry}>
          Retry
        </button>
      )}
    </div>
  )
}
