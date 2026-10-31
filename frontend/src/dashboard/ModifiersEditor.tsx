import { useState } from 'react'
import { deleteJson, postJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { useConfirm } from '../components/confirmContext'
import { LoadState } from '../components/LoadState'
import { checkbox, field, note } from './styles'
import { message, moved, needsReload, orderBody } from './catalogHelpers'
import { dashboardPaths, type AdminModifierGroup, type AdminModifierOption, type Removal } from './types'
import { Status } from './ui'

/**
 * An item's option groups ("Flavours: choose 3") and their options. Anything that has been sold is
 * kept, as inactive, so past sales still make sense; a group with no active option leaves the till.
 */
export function ModifiersEditor({ itemId, itemName }: { itemId: number; itemName: string }) {
  const confirm = useConfirm()
  const groups = useApi<AdminModifierGroup[]>(dashboardPaths.modifierGroups(itemId))
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)

  if (groups.status !== 'success') {
    return <LoadState status={groups.status} message={groups.status === 'error' ? groups.message : undefined} onRetry={groups.reload} />
  }
  const groupIds = groups.data.map((group) => group.id)

  /** Resolves true if the change went through (so a form can clear itself), false if it was refused. */
  async function run(action: () => Promise<unknown>, done?: (result: unknown) => string) {
    setStatus(null)
    try {
      const result = await action()
      groups.reload()
      if (done) setStatus({ tone: 'ok', text: done(result) })
      return true
    } catch (error) {
      setStatus({ tone: 'error', text: message(error) })
      // Saved by someone else meanwhile: show theirs.
      if (needsReload(error)) groups.reload()
      return false
    }
  }
  const removed = (what: string) => (result: unknown) =>
    (result as Removal).outcome === 'DELETED' ? `Deleted ${what}` : `${what} was sold before: kept, but no longer offered`

  return (
    <div className="flex flex-col gap-3" role="group" aria-label={`Options of ${itemName}`}>
      <Status status={status} />
      {groups.data.length === 0 && <p className={note}>No option groups: the till adds {itemName} straight to the sale.</p>}
      {groups.data.map((group, index) => (
        <GroupEditor key={`${group.id}-${group.version}-${group.name}-${group.chooseExactly}`} group={group} first={index === 0}
          last={index === groups.data.length - 1}
          onSave={(name, chooseExactly) => run(() => putJson(dashboardPaths.modifierGroup(group.id), { name, chooseExactly, version: group.version }))}
          onMove={(by) => {
            const order = moved(groupIds, group.id, by)
            if (order) void run(() => putJson(dashboardPaths.modifierGroupsOrder(itemId), orderBody(groups.data, order)))
          }}
          onRemove={async () => {
            if (await confirm({ title: `Remove the ${group.name} option group?`, message: `It is taken off ${itemName}. Past sales keep the options they were sold with.`, confirmLabel: 'Remove group' })) {
              void run(() => deleteJson<Removal>(dashboardPaths.modifierGroup(group.id)), removed(group.name))
            }
          }}
          run={run} removed={removed} />
      ))}
      <NewGroupForm onAdd={(name, chooseExactly) => run(() => postJson(dashboardPaths.modifierGroups(itemId), { name, chooseExactly }))} />
    </div>
  )
}

type Run = (action: () => Promise<unknown>, done?: (result: unknown) => string) => Promise<boolean>

function GroupEditor({ group, first, last, onSave, onMove, onRemove, run, removed }: {
  group: AdminModifierGroup
  first: boolean
  last: boolean
  onSave: (name: string, chooseExactly: number) => void
  onMove: (by: -1 | 1) => void
  onRemove: () => void
  run: Run
  removed: (what: string) => (result: unknown) => string
}) {
  const confirm = useConfirm()
  const [name, setName] = useState(group.name)
  const [picks, setPicks] = useState(String(group.chooseExactly))
  const optionIds = group.options.map((option) => option.id)
  const changed = name.trim() !== group.name || Number(picks) !== group.chooseExactly
  return (
    <div className="rounded-xl bg-white p-3 ring-1 ring-zinc-900/5">
      <div className="flex flex-wrap items-center gap-2">
        <input className={`${field} w-48`} aria-label={`Name of group ${group.name}`} maxLength={80} value={name}
          onChange={(event) => setName(event.target.value)} />
        <label className="flex items-center gap-1 text-sm">
          Choose
          <input className={`${field} w-16`} aria-label={`Picks of group ${group.name}`} type="number" min="1" max="99" value={picks}
            onChange={(event) => setPicks(event.target.value)} />
        </label>
        <button type="button" className="btn" aria-label={`Save group ${group.name}`}
          disabled={!changed || name.trim() === '' || Number(picks) < 1} onClick={() => onSave(name.trim(), Number(picks))}>Save</button>
        <button type="button" className="btn" disabled={first} aria-label={`Move group ${group.name} earlier`} onClick={() => onMove(-1)}>↑</button>
        <button type="button" className="btn" disabled={last} aria-label={`Move group ${group.name} later`} onClick={() => onMove(1)}>↓</button>
        <button type="button" className="btn" aria-label={`Remove group ${group.name}`} onClick={onRemove}>Remove group</button>
      </div>
      <ul className="mt-2 flex flex-col gap-1.5">
        {group.options.map((option, index) => (
          <OptionRow key={`${option.id}-${option.version}-${option.name}-${option.price}-${option.active}`} option={option} first={index === 0}
            last={index === group.options.length - 1}
            onSave={(next) => run(() => putJson(dashboardPaths.modifierOption(option.id), { ...next, version: option.version }))}
            onMove={(by) => {
              const order = moved(optionIds, option.id, by)
              if (order) void run(() => putJson(dashboardPaths.modifierOptionsOrder(group.id), orderBody(group.options, order)))
            }}
            onRemove={async () => {
              if (await confirm({ title: `Remove the ${option.name} option?`, message: 'Past sales keep the option they were sold with.', confirmLabel: 'Remove option' })) {
                void run(() => deleteJson<Removal>(dashboardPaths.modifierOption(option.id)), removed(option.name))
              }
            }} />
        ))}
      </ul>
      <NewOptionForm groupName={group.name}
        onAdd={(optionName, price) => run(() => postJson(dashboardPaths.modifierOptions(group.id), { name: optionName, price }))} />
    </div>
  )
}

function OptionRow({ option, first, last, onSave, onMove, onRemove }: {
  option: AdminModifierOption
  first: boolean
  last: boolean
  onSave: (next: { name: string; price: number; active: boolean }) => void
  onMove: (by: -1 | 1) => void
  onRemove: () => void
}) {
  const [name, setName] = useState(option.name)
  const [price, setPrice] = useState(option.price.toFixed(2))
  const [active, setActive] = useState(option.active)
  const changed = name.trim() !== option.name || Number(price) !== option.price || active !== option.active
  return (
    <li className={`flex flex-wrap items-center gap-2 ${option.active ? '' : 'opacity-55'}`}>
      <input className={`${field} w-44`} aria-label={`Name of option ${option.name}`} maxLength={80} value={name}
        onChange={(event) => setName(event.target.value)} />
      <input className={`${field} w-24`} aria-label={`Extra price of option ${option.name}`} type="number" min="0" step="0.10"
        value={price} onChange={(event) => setPrice(event.target.value)} />
      <label className="flex items-center gap-1 text-sm">
        <input className={checkbox} type="checkbox" aria-label={`Option ${option.name} on sale`} checked={active}
          onChange={(event) => setActive(event.target.checked)} />
        On sale
      </label>
      <button type="button" className="btn" aria-label={`Save option ${option.name}`}
        disabled={!changed || name.trim() === '' || price === ''} onClick={() => onSave({ name: name.trim(), price: Number(price), active })}>Save</button>
      <button type="button" className="btn" disabled={first} aria-label={`Move option ${option.name} earlier`} onClick={() => onMove(-1)}>↑</button>
      <button type="button" className="btn" disabled={last} aria-label={`Move option ${option.name} later`} onClick={() => onMove(1)}>↓</button>
      <button type="button" className="btn" aria-label={`Remove option ${option.name}`} onClick={onRemove}>Remove</button>
    </li>
  )
}

function NewOptionForm({ groupName, onAdd }: { groupName: string; onAdd: (name: string, price: number) => Promise<boolean> }) {
  const [name, setName] = useState('')
  const [price, setPrice] = useState('0')
  return (
    <div className="mt-2 flex flex-wrap items-center gap-2">
      <input className={`${field} w-44`} aria-label={`New option name for ${groupName}`} placeholder="New option…" maxLength={80}
        value={name} onChange={(event) => setName(event.target.value)} />
      <input className={`${field} w-24`} aria-label={`New option extra price for ${groupName}`} type="number" min="0" step="0.10"
        value={price} onChange={(event) => setPrice(event.target.value)} />
      <button type="button" className="btn" aria-label={`Add option to ${groupName}`} disabled={name.trim() === '' || price === ''}
        onClick={() => void onAdd(name.trim(), Number(price)).then((ok) => ok && setName(''))}>Add option</button>
    </div>
  )
}

function NewGroupForm({ onAdd }: { onAdd: (name: string, chooseExactly: number) => Promise<boolean> }) {
  const [name, setName] = useState('')
  const [picks, setPicks] = useState('1')
  return (
    <div className="flex flex-wrap items-center gap-2">
      <input className={`${field} w-48`} aria-label="New option group name" placeholder="New option group…" maxLength={80}
        value={name} onChange={(event) => setName(event.target.value)} />
      <label className="flex items-center gap-1 text-sm">
        Choose
        <input className={`${field} w-16`} aria-label="New option group picks" type="number" min="1" max="99" value={picks}
          onChange={(event) => setPicks(event.target.value)} />
      </label>
      <button type="button" className="btn" disabled={name.trim() === '' || Number(picks) < 1}
        onClick={() => void onAdd(name.trim(), Number(picks)).then((ok) => ok && setName(''))}>Add group</button>
    </div>
  )
}
