import { useState } from 'react'
import { ApiError, postJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { formatDate } from '../lib/dates'
import { checkbox, field, note, sectionTitle, table, tableAsCards, tableFrame } from './styles'
import { dashboardPaths, type AdminCategory, type AdminItem } from './types'
import { Page, Status } from './ui'

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'Could not reach the server. Try again.'
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

  if (catalogue.status !== 'success') {
    return <LoadState status={catalogue.status} message={catalogue.status === 'error' ? catalogue.message : undefined} onRetry={catalogue.reload} />
  }
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
      {categories.map((category) => (
        <section key={category.id} aria-label={category.name}>
          <h2 className={`${sectionTitle} flex items-baseline gap-2`}>
            {category.name}
            <span className="text-xs font-normal normal-case tracking-normal">{category.tab ?? 'Not on the grid'}</span>
          </h2>
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
                  <ItemRow key={item.id} item={item} />
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

function ItemRow({ item }: { item: AdminItem }) {
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

  return (
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
          onClick={() => void save()}>
          Save
        </button>
        <Status status={status} />
      </td>
    </tr>
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
