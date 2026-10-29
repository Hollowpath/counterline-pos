import { useState } from 'react'
import { deleteJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { confirmed, message, moved } from './catalogHelpers'
import { checkbox, field, note, sectionTitle } from './styles'
import { dashboardPaths, type AdminCategory, type AdminTab } from './types'

/** Quick-grid tabs: rename, reorder, and delete the ones with no folders. */
export function TabsPanel({ onChanged, onNotice }: { onChanged: () => void; onNotice: (text: string | null) => void }) {
  const tabs = useApi<AdminTab[]>(dashboardPaths.tabs)
  if (tabs.status !== 'success') {
    return tabs.status === 'error' ? <p className="text-sm text-red-600" role="alert">{tabs.message}</p> : null
  }
  const ids = tabs.data.map((tab) => tab.id)

  async function run(action: () => Promise<unknown>) {
    onNotice(null)
    try {
      await action()
      tabs.reload()
      onChanged()
    } catch (error) {
      onNotice(message(error))
    }
  }

  return (
    <section aria-label="Tabs">
      <h2 className={sectionTitle}>Tabs</h2>
      {tabs.data.length === 0 ? (
        <p className={note}>No tabs yet. A tab is created with the first folder placed on it.</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {tabs.data.map((tab, index) => (
            <TabRow key={`${tab.id}-${tab.name}`} tab={tab} first={index === 0} last={index === tabs.data.length - 1}
              onRename={(name) => run(() => putJson(dashboardPaths.tab(tab.id), { name }))}
              onMove={(by) => {
                const order = moved(ids, tab.id, by)
                return order ? run(() => putJson(dashboardPaths.tabsOrder, { ids: order })) : undefined
              }}
              onDelete={() => {
                if (confirmed(`Delete the ${tab.name} tab?`)) void run(() => deleteJson(dashboardPaths.tab(tab.id)))
              }} />
          ))}
        </ul>
      )}
    </section>
  )
}

function TabRow({ tab, first, last, onRename, onMove, onDelete }: {
  tab: AdminTab
  first: boolean
  last: boolean
  onRename: (name: string) => void
  onMove: (by: -1 | 1) => void
  onDelete: () => void
}) {
  const [name, setName] = useState(tab.name)
  return (
    <li className="flex flex-wrap items-center gap-2">
      <input className={`${field} w-56`} aria-label={`Name of tab ${tab.name}`} maxLength={60} value={name}
        onChange={(event) => setName(event.target.value)} />
      <button type="button" className="btn" disabled={name.trim() === '' || name.trim() === tab.name}
        aria-label={`Save tab ${tab.name}`} onClick={() => onRename(name.trim())}>Save</button>
      <button type="button" className="btn" disabled={first} aria-label={`Move tab ${tab.name} earlier`} onClick={() => onMove(-1)}>↑</button>
      <button type="button" className="btn" disabled={last} aria-label={`Move tab ${tab.name} later`} onClick={() => onMove(1)}>↓</button>
      <button type="button" className="btn" disabled={tab.folders > 0} aria-label={`Delete tab ${tab.name}`} onClick={onDelete}>Delete</button>
      <span className={note}>{tab.folders === 0 ? 'Empty' : `${tab.folders} ${tab.folders === 1 ? 'folder' : 'folders'}`}</span>
    </li>
  )
}

/** One folder's settings: name, tab, whether it counts for "Buy 3", off the grid, and delete when empty. */
export function FolderToolbar({ category, tabs, onChanged, onNotice }: {
  category: AdminCategory
  tabs: string[]
  onChanged: () => void
  onNotice: (text: string | null) => void
}) {
  const [name, setName] = useState(category.name)
  const [tab, setTab] = useState(category.tab ?? '')
  const [counts, setCounts] = useState(category.countsForPromotions)
  const changed = name.trim() !== category.name || tab.trim() !== (category.tab ?? '') || counts !== category.countsForPromotions

  async function run(action: () => Promise<unknown>, done: string) {
    onNotice(null)
    try {
      await action()
      onNotice(done)
      onChanged()
    } catch (error) {
      onNotice(message(error))
    }
  }

  function save(tabName: string) {
    return run(() => putJson(dashboardPaths.folder(category.id), {
      name: name.trim(),
      tab: tabName.trim() === '' ? null : tabName.trim(),
      // Moving to another tab takes that tab's first free cell; staying keeps the cell.
      countsForPromotions: counts,
    }), `Saved ${name.trim()}`)
  }

  return (
    <div className="mb-3 flex flex-wrap items-end gap-3 text-sm">
      <label className="flex flex-col gap-1">
        Rename
        <input className={`${field} w-52`} aria-label={`Name of folder ${category.name}`} maxLength={80} value={name}
          onChange={(event) => setName(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        On tab
        <input className={`${field} w-44`} aria-label={`Tab of folder ${category.name}`} maxLength={60} list={`tabs-of-${category.id}`}
          placeholder="Not on the grid" value={tab} onChange={(event) => setTab(event.target.value)} />
        <datalist id={`tabs-of-${category.id}`}>{tabs.map((existing) => <option key={existing} value={existing} />)}</datalist>
      </label>
      <label className="flex items-center gap-2 pb-2">
        <input className={checkbox} type="checkbox" aria-label={`${category.name} counts for promotions`} checked={counts}
          onChange={(event) => setCounts(event.target.checked)} />
        Counts for “Buy 3”
      </label>
      <button type="button" className="btn btn-primary" disabled={!changed || name.trim() === ''}
        aria-label={`Save folder ${category.name}`} onClick={() => void save(tab)}>Save</button>
      {category.tab !== null && (
        <button type="button" className="btn" aria-label={`Remove ${category.name} from the till grid`}
          onClick={() => {
            if (confirmed(`Remove ${category.name} from the till grid? Its items stay in the library.`)) {
              setTab('')
              void save('')
            }
          }}>
          Remove from till grid
        </button>
      )}
      <button type="button" className="btn" disabled={category.items.length > 0} aria-label={`Delete folder ${category.name}`}
        title={category.items.length > 0 ? 'Move or remove its items first' : undefined}
        onClick={() => {
          if (confirmed(`Delete the empty folder ${category.name}?`)) {
            void run(() => deleteJson(dashboardPaths.folder(category.id)), `Deleted ${category.name}`)
          }
        }}>
        Delete folder
      </button>
    </div>
  )
}
