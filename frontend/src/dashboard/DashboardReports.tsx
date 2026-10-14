import { useState } from 'react'
import { useApi } from '../api/useApi'
import type { Outlet } from '../auth/types'
import { LoadState } from '../components/LoadState'
import { ReportsScreen } from '../features/reports/ReportsScreen'
import { field } from './styles'
import { dashboardPaths } from './types'
import { Page } from './ui'

/** The POS's reports, for any outlet this user can see (head office: every outlet). */
export function DashboardReports() {
  const outlets = useApi<Outlet[]>(dashboardPaths.outlets)
  const [chosen, setChosen] = useState<number | null>(null)

  if (outlets.status !== 'success') {
    return <LoadState status={outlets.status} message={outlets.status === 'error' ? outlets.message : undefined} onRetry={outlets.reload} />
  }
  const outlet = outlets.data.find((entry) => entry.id === chosen) ?? outlets.data[0]
  if (!outlet) return <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No outlets.</p>

  return (
    <Page
      label="Outlet reports"
      title="Reports"
      description={`The till's reports for ${outlet.name}, for any day or dates.`}
      actions={outlets.data.length > 1 && (
        <select aria-label="Outlet" className={`${field} max-md:w-full`} value={outlet.id} onChange={(event) => setChosen(Number(event.target.value))}>
          {outlets.data.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.name}
            </option>
          ))}
        </select>
      )}
      flush
    >
      <div className="min-h-0 flex-1 [&>*]:h-full!">
        <ReportsScreen
          key={outlet.id}
          outletName={outlet.name}
          pathFor={(name, from, to) => dashboardPaths.report(outlet.id, name, from, to)}
        />
      </div>
    </Page>
  )
}
