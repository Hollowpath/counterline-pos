import { useState, type FormEvent } from 'react'
import { postJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { confirmed, message } from './catalogHelpers'
import { field, note, noticeClass, sectionTitle, table, tableFrame } from './styles'
import { dashboardPaths, type AdminOutlet } from './types'
import { Page } from './ui'

type Notice = { tone: 'ok' | 'error'; text: string } | null

/**
 * Head office's outlets: add one, change its name and address, close it or open it again. The code
 * is how an outlet is named at the till and in reports, so it is set once. Outlets are never deleted:
 * their sales stay.
 */
export function OutletsAdmin() {
  const outlets = useApi<AdminOutlet[]>(dashboardPaths.adminOutlets)
  const [notice, setNotice] = useState<Notice>(null)

  if (outlets.status !== 'success') {
    return <LoadState status={outlets.status} message={outlets.status === 'error' ? outlets.message : undefined} onRetry={outlets.reload} />
  }

  function changed(text: string) {
    setNotice({ tone: 'ok', text })
    outlets.reload()
  }

  return (
    <Page title="Outlets" description="The chain's shops. An outlet's code is set when it is added and cannot be changed. A closed outlet stops trading but keeps its sales.">
      {notice && <p className={noticeClass(notice.tone)} role="status">{notice.text}</p>}
      <NewOutletForm onAdded={(outlet) => changed(`Added ${outlet.name}`)} onError={(text) => setNotice({ tone: 'error', text })} />
      <section aria-label="Outlet list">
        <h2 className={sectionTitle}>All outlets</h2>
        <div className={tableFrame}>
          <table className={table}>
            <thead>
              <tr>
                <th>Code</th>
                <th>Name</th>
                <th>Address</th>
                <th>Status</th>
                <th aria-label="Actions" />
              </tr>
            </thead>
            <tbody>
              {outlets.data.map((outlet) => (
                <OutletRow key={`${outlet.id}-${outlet.name}-${outlet.address}-${outlet.active}`} outlet={outlet}
                  onChanged={changed} onError={(text) => setNotice({ tone: 'error', text })} />
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </Page>
  )
}

function NewOutletForm({ onAdded, onError }: { onAdded: (outlet: AdminOutlet) => void; onError: (text: string) => void }) {
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [address, setAddress] = useState('')
  const [busy, setBusy] = useState(false)

  async function add(event: FormEvent) {
    event.preventDefault()
    setBusy(true)
    try {
      const outlet = await postJson<AdminOutlet>(dashboardPaths.adminOutlets, { code, name, address })
      setCode('')
      setName('')
      setAddress('')
      onAdded(outlet)
    } catch (error) {
      // The form keeps what was typed, so it can be corrected.
      onError(message(error))
    } finally {
      setBusy(false)
    }
  }

  const label = 'flex flex-col gap-1 text-sm font-medium text-zinc-700'
  return (
    <form aria-label="Add an outlet" className="flex flex-wrap items-end gap-3 rounded-2xl bg-white p-4 shadow-sm ring-1 ring-zinc-900/5" onSubmit={(event) => void add(event)}>
      <label className={label}>
        Code
        <input className={`${field} w-28`} required maxLength={10} value={code} onChange={(event) => setCode(event.target.value)} />
      </label>
      <label className={label}>
        Name
        <input className={field} required maxLength={80} value={name} onChange={(event) => setName(event.target.value)} />
      </label>
      <label className={`${label} min-w-64 flex-1`}>
        Address
        <input className={field} maxLength={200} value={address} onChange={(event) => setAddress(event.target.value)} />
      </label>
      <button type="submit" className="btn btn-primary" disabled={busy}>Add outlet</button>
    </form>
  )
}

function OutletRow({ outlet, onChanged, onError }: {
  outlet: AdminOutlet
  onChanged: (text: string) => void
  onError: (text: string) => void
}) {
  const [name, setName] = useState(outlet.name)
  const [address, setAddress] = useState(outlet.address ?? '')
  const [busy, setBusy] = useState(false)
  const dirty = name !== outlet.name || address !== (outlet.address ?? '')

  async function run(action: () => Promise<unknown>, done: string) {
    setBusy(true)
    try {
      await action()
      onChanged(done)
    } catch (error) {
      onError(message(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <tr>
      <td className="font-semibold">{outlet.code}</td>
      <td>
        <input className={field} aria-label={`Name of outlet ${outlet.code}`} value={name} maxLength={80}
          onChange={(event) => setName(event.target.value)} />
      </td>
      <td>
        <input className={field} aria-label={`Address of outlet ${outlet.code}`} value={address} maxLength={200}
          onChange={(event) => setAddress(event.target.value)} />
      </td>
      <td>
        {outlet.active ? 'Open' : 'Closed'}
        {!outlet.active && <span className={note}>Deactivated</span>}
      </td>
      <td className="whitespace-nowrap text-right">
        <button type="button" className="btn" disabled={busy || !dirty} aria-label={`Save outlet ${outlet.code}`}
          onClick={() => void run(() => putJson(dashboardPaths.adminOutlet(outlet.id), { name, address }), `Saved ${name}`)}>
          Save
        </button>{' '}
        {outlet.active ? (
          <button type="button" className="btn btn-danger" disabled={busy} aria-label={`Deactivate outlet ${outlet.code}`}
            onClick={() => {
              if (!confirmed(`Deactivate ${outlet.name}? It stops trading and its staff cannot sign in; its sales are kept.`)) return
              void run(() => postJson(dashboardPaths.adminOutletAction(outlet.id, 'deactivate'), {}), `Deactivated ${outlet.name}`)
            }}>
            Deactivate
          </button>
        ) : (
          <button type="button" className="btn" disabled={busy} aria-label={`Reactivate outlet ${outlet.code}`}
            onClick={() => void run(() => postJson(dashboardPaths.adminOutletAction(outlet.id, 'reactivate'), {}), `Reactivated ${outlet.name}`)}>
            Reactivate
          </button>
        )}
      </td>
    </tr>
  )
}
