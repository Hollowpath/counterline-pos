import { useState } from 'react'
import { ApiError, postJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { checkbox, field, table, tableAsCards, tableFrame } from './styles'
import { dashboardPaths, type AdminDiscount } from './types'
import { Page, Status } from './ui'

type Draft = Omit<AdminDiscount, 'id'>

const EMPTY: Draft = { name: '', kind: 'PERCENT', value: 10, scope: 'BOTH', requiresApproval: false, notForMembers: false, countsAsVoucher: false, active: true }

/** The editable fields, always in the same order (so a draft and what was saved compare as text). */
function toDraft(discount: AdminDiscount): Draft {
  return {
    name: discount.name, kind: discount.kind, value: discount.value, scope: discount.scope, requiresApproval: discount.requiresApproval,
    notForMembers: discount.notForMembers, countsAsVoucher: discount.countsAsVoucher, active: discount.active,
  }
}

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'Offline'
}

/** Head office: the discounts every till offers (one per sale), which need a manager, and which are not for members. */
export function DiscountsAdmin() {
  const discounts = useApi<AdminDiscount[]>(dashboardPaths.discounts)
  if (discounts.status !== 'success') {
    return <LoadState status={discounts.status} message={discounts.status === 'error' ? discounts.message : undefined} onRetry={discounts.reload} />
  }
  return (
    <Page
      title="Discounts"
      description="Offered at every outlet’s till; a sale takes one. Past sales keep their terms. With a voucher or any discount, a sale gives no Buy 3 Free 1, seasonal or birthday free items."
    >
      <div className={tableFrame}>
        <table className={`${table} ${tableAsCards}`}>
          <thead>
            <tr>
              <th>Name</th>
              <th>Type</th>
              <th>Value</th>
              <th>Where</th>
              <th>Manager PIN</th>
              <th>Not with a member</th>
              <th title="A paper voucher keyed in at the till">Voucher</th>
              <th>Offered</th>
              <th aria-label="Save" />
            </tr>
          </thead>
          <tbody>
            {discounts.data.map((discount) => (
              <DiscountRow key={discount.id} initial={discount} />
            ))}
            <DiscountRow key={`new-${discounts.data.length}`} initial={null} onAdded={discounts.reload} />
          </tbody>
        </table>
      </div>
    </Page>
  )
}

function DiscountRow({ initial, onAdded }: { initial: AdminDiscount | null; onAdded?: () => void }) {
  const [saved, setSaved] = useState<Draft>(initial ? toDraft(initial) : EMPTY)
  const [draft, setDraft] = useState<Draft>(initial ? toDraft(initial) : EMPTY)
  const [status, setStatus] = useState<{ tone: 'ok' | 'error'; text: string } | null>(null)
  const isNew = initial === null
  const changed = JSON.stringify(draft) !== JSON.stringify(saved)
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => setDraft({ ...draft, [key]: value })
  const label = isNew ? 'new discount' : saved.name

  async function save() {
    setStatus(null)
    const body = { ...draft, name: draft.name.trim(), value: Number(draft.value) }
    try {
      if (isNew) {
        await postJson(dashboardPaths.discounts, body)
        onAdded?.()
      } else {
        setSaved(toDraft(await putJson<AdminDiscount>(dashboardPaths.discount(initial.id), body)))
        setStatus({ tone: 'ok', text: 'Saved' })
      }
    } catch (error) {
      setStatus({ tone: 'error', text: message(error) })
    }
  }

  return (
    <tr className={isNew ? 'bg-zinc-100/60' : draft.active ? undefined : 'opacity-55'}>
      <td>
        <input className={`${field} w-full min-w-44`} aria-label={`Name of ${label}`} placeholder={isNew ? 'New discount…' : undefined}
          maxLength={80} value={draft.name} onChange={(event) => set('name', event.target.value)} />
      </td>
      <td data-label="Type">
        <select className={field} aria-label={`Type of ${label}`} value={draft.kind} onChange={(event) => set('kind', event.target.value as Draft['kind'])}>
          <option value="PERCENT">%</option>
          <option value="AMOUNT">RM</option>
        </select>
      </td>
      <td data-label="Value">
        <input className={`${field} w-24`} aria-label={`Value of ${label}`} type="number" min="0.01" step="0.01" value={draft.value}
          onChange={(event) => set('value', Number(event.target.value))} />
      </td>
      <td data-label="Where">
        <select className={field} aria-label={`Where ${label} is offered`} value={draft.scope}
          onChange={(event) => set('scope', event.target.value as Draft['scope'])}>
          <option value="BOTH">Item &amp; order</option>
          <option value="ITEM">Item</option>
          <option value="ORDER">Order (% tab)</option>
        </select>
      </td>
      <td data-label="Manager PIN">
        <input className={checkbox} type="checkbox" aria-label={`${label} needs a manager PIN`} checked={draft.requiresApproval}
          onChange={(event) => set('requiresApproval', event.target.checked)} />
      </td>
      <td data-label="Not with a member">
        <input className={checkbox} type="checkbox" aria-label={`${label} can’t be given with a member`} checked={draft.notForMembers}
          onChange={(event) => set('notForMembers', event.target.checked)} />
      </td>
      <td data-label="Voucher">
        <input className={checkbox} type="checkbox" aria-label={`${label} counts as a voucher (no free items)`} checked={draft.countsAsVoucher}
          onChange={(event) => set('countsAsVoucher', event.target.checked)} />
      </td>
      <td data-label={isNew ? undefined : 'Offered'}>
        {!isNew && (
          <input className={checkbox} type="checkbox" aria-label={`${label} offered`} checked={draft.active}
            onChange={(event) => set('active', event.target.checked)} />
        )}
      </td>
      <td className="whitespace-nowrap">
        <button type="button" className={isNew ? 'btn' : 'btn btn-primary'}
          disabled={!changed || draft.name.trim() === ''} onClick={() => void save()}>
          {isNew ? 'Add' : 'Save'}
        </button>
        <Status status={status} />
      </td>
    </tr>
  )
}
