import { useState, type FormEvent } from 'react'
import { ApiError, deleteJson, postJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { useConfirm } from '../components/confirmContext'
import { LoadState } from '../components/LoadState'
import { formatDate } from '../lib/dates'
import { checkbox, field, note, noticeClass, sectionTitle, table, tableAsCards, tableFrame } from './styles'
import { moved } from './catalogHelpers'
import { FolderToolbar, TabsPanel } from './CatalogStructure'
import { ModifiersEditor } from './ModifiersEditor'
import { dashboardPaths, type AdminCategory, type AdminItem, type Notice, type Removal } from './types'
import { Page, Status } from './ui'

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'Offline'
}

const priceField = `${field} w-28`

/**
 * Head office: every folder's items, prices and whether they are on sale, for every outlet's till.
 * Past sales keep the name and price they were sold at.
 */
export function ItemsAdmin() {
  const catalogue = useApi<AdminCategory[]>(dashboardPaths.items)
  const [filter, setFilter] = useState('')
  const [showOffGrid, setShowOffGrid] = useState(false)
  // What the last structural change did, kept across the reload that follows it.
  const [notice, setNotice] = useState<Notice | null>(null)

  if (catalogue.status !== 'success') {
    return <LoadState status={catalogue.status} message={catalogue.status === 'error' ? catalogue.message : undefined} onRetry={catalogue.reload} />
  }
  const tabNames = [...new Set(catalogue.data.flatMap((category) => (category.tab === null ? [] : [category.tab])))]
  const folders = catalogue.data.map((category) => ({ id: category.id, name: category.name }))
  const term = filter.trim().toLowerCase()
  const categories = catalogue.data
    .map((category) => ({ ...category, items: category.items.filter((item) => item.name.toLowerCase().includes(term)) }))
    .filter((category) => showOffGrid || category.tab !== null)
    .filter((category) => term === '' || category.items.length > 0 || category.name.toLowerCase().includes(term))

  return (
    <Page
      label="Items and prices"
      title="Items & Prices"
      description={
        <>
          Changes reach every outlet’s till at once. Past sales keep their price. Tick “Seasonal” on a seasonal
          flavour and set its last day: until then, every 3 items bought with one among them earn a free baby can
          (Seasonal Flavour Promo). After it, the item is an ordinary one again.
        </>
      }
      actions={
        <>
          <input
            className={`${field} w-64 max-md:w-full`}
            type="search"
            placeholder="Find an item…"
            aria-label="Find an item"
            value={filter}
            onChange={(event) => setFilter(event.target.value)}
          />
          <label className="flex items-center gap-2 text-sm">
            <input className={checkbox} type="checkbox" checked={showOffGrid} onChange={(event) => setShowOffGrid(event.target.checked)} />
            Show folders not on the till
          </label>
        </>
      }
    >
      {notice && <p className={noticeClass(notice.tone)} role={notice.tone === 'error' ? 'alert' : 'status'}>{notice.text}</p>}
      <NewFolderForm
        tabs={tabNames}
        empty={catalogue.data.length === 0}
        onAdded={catalogue.reload}
      />
      <TabsPanel onChanged={catalogue.reload} onNotice={setNotice} />
      {categories.map((category) => (
        <section key={category.id} aria-label={category.name}>
          <h2 className={`${sectionTitle} flex items-baseline gap-2`}>
            {category.name}
            <span className="text-xs font-normal normal-case tracking-normal">{category.tab ?? 'Not on the grid'}</span>
          </h2>
          <FolderToolbar category={category} tabs={tabNames} onChanged={catalogue.reload} onNotice={setNotice} />
          <div className={tableFrame}>
            <table className={`${table} ${tableAsCards}`}>
              <thead>
                <tr>
                  <th>Item</th>
                  <th>Price (RM)</th>
                  <th>On sale</th>
                  <th>Seasonal</th>
                  <th aria-label="Save" />
                </tr>
              </thead>
              <tbody>
                {category.items.map((item) => (
                  <ItemRow key={item.id} item={item} folderId={category.id} folders={folders}
                    folderItemIds={catalogue.data.find((all) => all.id === category.id)?.items.map((it) => it.id) ?? []}
                    canReorder={term === ''} onChanged={catalogue.reload} onNotice={setNotice} />
                ))}
                <NewItemRow categoryId={category.id} onAdded={catalogue.reload} />
              </tbody>
            </table>
          </div>
        </section>
      ))}
    </Page>
  )
}

/**
 * Adds a folder to the till grid: on the tab of that name (a new tab if there is none), in its first
 * free cell. The way to start a catalogue from nothing; items are then added inside the folder.
 */
function NewFolderForm({ tabs, empty, onAdded }: { tabs: string[]; empty: boolean; onAdded: () => void }) {
  const [name, setName] = useState('')
  const [tab, setTab] = useState<string | null>(null)
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  // Until chosen, the first existing tab (or a suggested name for the first one).
  const tabName = tab ?? tabs[0] ?? 'Selling Items'

  async function add(event: FormEvent) {
    event.preventDefault()
    setStatus(null)
    try {
      const added = await postJson<AdminCategory>(dashboardPaths.folders, { name: name.trim(), tab: tabName.trim() })
      setName('')
      setStatus({ tone: 'ok', text: `Added ${added.name} to ${added.tab}` })
      onAdded()
    } catch (error) {
      setStatus({ tone: 'error', text: message(error) })
    }
  }

  return (
    <section aria-label="Add folder">
      <h2 className={sectionTitle}>Add folder</h2>
      {empty && (
        <p className={`${note} mb-3`}>
          There are no folders yet. Add a folder, then add its first item below it. Folders appear on the till’s grid, on the tab you name.
        </p>
      )}
      <form className="flex flex-wrap items-end gap-3" onSubmit={(event) => void add(event)}>
        <label className="flex flex-col gap-1 text-sm">
          Folder name
          <input className={`${field} w-56`} maxLength={80} value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Tab
          <input className={`${field} w-56`} maxLength={60} list="folder-tabs" value={tabName}
            onChange={(event) => setTab(event.target.value)} />
          <datalist id="folder-tabs">
            {tabs.map((existing) => <option key={existing} value={existing} />)}
          </datalist>
        </label>
        <button type="submit" className="btn btn-primary" disabled={name.trim() === '' || tabName.trim() === ''}>
          Add folder
        </button>
        <Status status={status} />
      </form>
    </section>
  )
}

function ItemRow({ item, folderId, folders, folderItemIds, canReorder, onChanged, onNotice }: {
  item: AdminItem
  folderId: number
  folders: { id: number; name: string }[]
  folderItemIds: number[]
  canReorder: boolean
  onChanged: () => void
  onNotice: (notice: Notice | null) => void
}) {
  const confirm = useConfirm()
  const [showOptions, setShowOptions] = useState(false)
  const [saved, setSaved] = useState(item)
  const [name, setName] = useState(item.name)
  const [price, setPrice] = useState(item.price.toFixed(2))
  const [active, setActive] = useState(item.active)
  const [seasonal, setSeasonal] = useState(item.seasonal)
  const [seasonalFrom, setSeasonalFrom] = useState(item.seasonalFrom ?? '')
  const [seasonalUntil, setSeasonalUntil] = useState(item.seasonalUntil ?? '')
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const changed = name.trim() !== saved.name || Number(price) !== saved.price || active !== saved.active
    || seasonal !== saved.seasonal || (seasonal && (seasonalFrom !== (saved.seasonalFrom ?? '') || seasonalUntil !== (saved.seasonalUntil ?? '')))
  // A seasonal flavour is for a limited time: it needs its last day.
  const incomplete = seasonal && seasonalUntil === ''

  async function save() {
    setStatus(null)
    try {
      const next = await putJson<AdminItem>(dashboardPaths.item(item.id), {
        name: name.trim(), price: Number(price), active, seasonal,
        seasonalFrom: seasonal && seasonalFrom !== '' ? seasonalFrom : null,
        seasonalUntil: seasonal ? seasonalUntil : null,
      })
      setSaved(next)
      setStatus({ tone: 'ok', text: 'Saved' })
    } catch (error) {
      setStatus({ tone: 'error', text: message(error) })
    }
  }

  async function structural(action: () => Promise<unknown>, done: (result: unknown) => string) {
    onNotice(null)
    try {
      const result = await action()
      onNotice({ tone: 'ok', text: done(result) })
      onChanged()
    } catch (error) {
      onNotice({ tone: 'error', text: message(error) })
    }
  }
  const order = (by: -1 | 1) => {
    const next = moved(folderItemIds, item.id, by)
    if (next) void structural(() => putJson(dashboardPaths.folderItemsOrder(folderId), { ids: next }), () => `Moved ${saved.name}`)
  }

  return (
    <>
    <tr className={active ? undefined : 'opacity-55'}>
      <td>
        <input className={`${field} w-full min-w-48`} aria-label={`Name of ${saved.name}`} maxLength={120} value={name}
          onChange={(event) => setName(event.target.value)} />
        {item.promotion && <span className={note}>Free with {item.promotion}</span>}
      </td>
      <td data-label="Price (RM)">
        <input
          className={priceField}
          aria-label={`Price of ${saved.name}`}
          type="number"
          min="0"
          step="0.10"
          inputMode="decimal"
          value={price}
          onChange={(event) => setPrice(event.target.value)}
        />
      </td>
      <td data-label="On sale">
        <input className={checkbox} type="checkbox" aria-label={`${saved.name} on sale`} checked={active}
          onChange={(event) => setActive(event.target.checked)} />
      </td>
      <td data-label={item.promotion ? undefined : 'Seasonal'}>
        {!item.promotion && (
          <>
            <input className={checkbox} type="checkbox" aria-label={`${saved.name} is a seasonal flavour`} checked={seasonal}
              onChange={(event) => setSeasonal(event.target.checked)} />
            {seasonal && (
              <span className="mt-1.5 flex flex-wrap gap-2 text-xs text-zinc-500">
                <label className="flex flex-col gap-0.5">
                  From
                  <input className={field} type="date" aria-label={`${saved.name} seasonal from`} value={seasonalFrom}
                    max={seasonalUntil || undefined} onChange={(event) => setSeasonalFrom(event.target.value)} />
                </label>
                <label className="flex flex-col gap-0.5">
                  Until *
                  <input className={field} type="date" aria-label={`${saved.name} seasonal until`} value={seasonalUntil}
                    min={seasonalFrom || undefined} required onChange={(event) => setSeasonalUntil(event.target.value)} />
                </label>
              </span>
            )}
            {saved.seasonal && !changed && <SeasonStatus item={saved} />}
          </>
        )}
      </td>
      <td className="whitespace-nowrap">
        <button type="button" className="btn btn-primary" disabled={!changed || incomplete || name.trim() === '' || price === ''}
          aria-label={`Save ${saved.name}`} onClick={() => void save()}>
          Save
        </button>
        <Status status={status} />
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <button type="button" className="btn" disabled={!canReorder || folderItemIds[0] === item.id}
            aria-label={`Move ${saved.name} up`} onClick={() => order(-1)}>↑</button>
          <button type="button" className="btn" disabled={!canReorder || folderItemIds[folderItemIds.length - 1] === item.id}
            aria-label={`Move ${saved.name} down`} onClick={() => order(1)}>↓</button>
          <select className={field} aria-label={`Folder of ${saved.name}`} value={folderId}
            onChange={(event) => {
              const target = folders.find((folder) => folder.id === Number(event.target.value))
              if (target) {
                void structural(() => putJson(dashboardPaths.item(item.id), {
                  categoryId: target.id, name: saved.name, price: saved.price, active: saved.active,
                }), () => `Moved ${saved.name} to ${target.name}`)
              }
            }}>
            {folders.map((folder) => <option key={folder.id} value={folder.id}>{folder.name}</option>)}
          </select>
          <button type="button" className="btn" aria-expanded={showOptions} aria-label={`Options of ${saved.name}`}
            onClick={() => setShowOptions(!showOptions)}>Options</button>
          <button type="button" className="btn" aria-label={`Remove ${saved.name}`}
            onClick={async () => {
              if (await confirm({ title: `Remove ${saved.name}?`, message: 'An item that has been sold is only taken off sale, so past sales stay intact; one that was never sold is deleted.', confirmLabel: 'Remove item' })) {
                void structural(() => deleteJson<Removal>(dashboardPaths.item(item.id)), (result) =>
                  (result as Removal).outcome === 'DELETED'
                    ? `Deleted ${saved.name}`
                    : `${saved.name} was sold before: taken off sale, with its history kept`)
              }
            }}>Remove</button>
        </div>
      </td>
    </tr>
    {showOptions && (
      <tr>
        <td colSpan={5}><ModifiersEditor itemId={item.id} itemName={saved.name} /></td>
      </tr>
    )}
    </>
  )
}

/** Whether a seasonal flavour is in season today, starts later, or has ended. */
function SeasonStatus({ item }: { item: AdminItem }) {
  if (item.seasonalNow) {
    return <span className={`${note} font-semibold text-green-600`}>In season{item.seasonalUntil && ` until ${formatDate(item.seasonalUntil)}`}</span>
  }
  if (item.seasonalUntil && new Date(`${item.seasonalUntil}T23:59:59`) < new Date()) {
    return <span className={`${note} text-red-600`}>Ended {formatDate(item.seasonalUntil)}: an ordinary item now</span>
  }
  return <span className={note}>Starts {item.seasonalFrom && formatDate(item.seasonalFrom)}</span>
}

function NewItemRow({ categoryId, onAdded }: { categoryId: number; onAdded: () => void }) {
  const [name, setName] = useState('')
  const [price, setPrice] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function add() {
    setError(null)
    try {
      await postJson(dashboardPaths.items, { categoryId, name: name.trim(), price: Number(price) })
      setName('')
      setPrice('')
      onAdded()
    } catch (caught) {
      setError(message(caught))
    }
  }

  return (
    <tr className="bg-zinc-100/60">
      <td>
        <input className={`${field} w-full min-w-48`} aria-label="New item name" placeholder="New item…" maxLength={120} value={name}
          onChange={(event) => setName(event.target.value)} />
      </td>
      <td data-label="Price (RM)">
        <input className={priceField} aria-label="New item price" type="number" min="0" step="0.10" value={price}
          onChange={(event) => setPrice(event.target.value)} />
      </td>
      <td />
      <td />
      <td className="whitespace-nowrap">
        <button type="button" className="btn" disabled={name.trim() === '' || price === ''} onClick={() => void add()}>
          Add
        </button>
        {error && <span className="ml-2 text-sm text-red-600" role="alert">{error}</span>}
      </td>
    </tr>
  )
}
