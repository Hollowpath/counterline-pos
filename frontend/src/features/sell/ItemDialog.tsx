import { useState } from 'react'
import { useApi } from '../../api/useApi'
import { Dialog } from '../../components/Dialog'
import { LoadState } from '../../components/LoadState'
import { formatRM, toSen } from '../../lib/money'
import { catalogPaths, type ItemOptions, type ModifierGroup } from '../catalog/types'
import { lineTotalSen, type CartLine, type CartModifier } from './cart'
import { formatDiscount, type DiscountRule } from './pricing'
import { dialogActions } from '../../styles/ui'

type LineDraft = Omit<CartLine, 'key'>

interface ItemDialogProps {
  /** The line being added (quantity 1, nothing chosen yet) or edited. */
  initial: LineDraft
  /** Load the item's option groups (items flagged hasOptions, or a line that already has options). */
  withOptions: boolean
  mode: 'add' | 'edit'
  /** The discount already elsewhere in the sale, if any: a sale takes only one. */
  /** Another line or the order already has the sale's one discount. */
  discountElsewhere: boolean
  onSave: (line: LineDraft) => void
  onRemove?: () => void
  onCancel: () => void
}

/** The item dialog: options ("CHOOSE N"), notes and quantity, and a line discount. */
/** A section with its small uppercase heading ("NOTES AND QUANTITY", "DISCOUNTS CHOOSE 1"). */
const section = '[&_h3]:mb-2.5 [&_h3]:flex [&_h3]:items-baseline [&_h3]:gap-2 [&_h3]:text-xs [&_h3]:font-semibold [&_h3]:tracking-wider [&_h3]:text-zinc-900 [&_h3]:uppercase'
/** [− 2 +] */
const stepper = [
  'inline-flex items-center gap-0.5 rounded-xl bg-zinc-100 p-0.5 text-zinc-900 [&_span]:min-w-8 [&_span]:text-center [&_span]:font-semibold [&_span]:tabular-nums',
  '[&_button]:grid [&_button]:size-9 [&_button]:place-items-center [&_button]:rounded-lg [&_button]:bg-white [&_button]:text-lg [&_button]:shadow-xs',
  '[&_button]:ring-1 [&_button]:ring-zinc-900/5 [&_button]:transition [&_button:enabled:hover]:text-indigo-600 [&_button:enabled:active]:scale-90',
].join(' ')

export function ItemDialog(props: ItemDialogProps) {
  const { itemId } = props.initial
  return props.withOptions && itemId !== null
    ? <WithItemOptions {...props} itemId={itemId} />
    : <WithItemDiscounts {...props} groups={[]} />
}

function WithItemOptions(props: ItemDialogProps & { itemId: number }) {
  const options = useApi<ItemOptions>(catalogPaths.itemOptions(props.itemId))
  if (options.status !== 'success') {
    return <Loading {...props} state={options} />
  }
  return <WithItemDiscounts {...props} groups={options.data.groups} />
}

function WithItemDiscounts(props: ItemDialogProps & { groups: ModifierGroup[] }) {
  // Promotional items are free and cannot take a discount, so there is nothing to offer.
  return props.initial.promotion ? <ItemForm {...props} offeredDiscounts={[]} /> : <WithOfferedDiscounts {...props} />
}

function WithOfferedDiscounts(props: ItemDialogProps & { groups: ModifierGroup[] }) {
  const discounts = useApi<DiscountRule[]>(catalogPaths.discounts('ITEM'))
  if (discounts.status !== 'success') {
    return <Loading {...props} state={discounts} />
  }
  return <ItemForm {...props} offeredDiscounts={discounts.data} />
}

function Loading({ initial, onCancel, state }: ItemDialogProps & {
  state: { status: 'loading' } | { status: 'error'; message: string; reload: () => void }
}) {
  return (
    <Dialog title={initial.name} onClose={onCancel}>
      <LoadState
        status={state.status}
        message={state.status === 'error' ? state.message : undefined}
        onRetry={state.status === 'error' ? state.reload : undefined}
      />
      <div className={dialogActions}>
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </Dialog>
  )
}

function ItemForm({ initial, mode, groups, offeredDiscounts, discountElsewhere, onSave, onRemove, onCancel }: ItemDialogProps & {
  groups: ModifierGroup[]
  offeredDiscounts: DiscountRule[]
}) {
  const [picks, setPicks] = useState<Record<number, number>>(() =>
    Object.fromEntries(initial.modifiers.map((modifier) => [modifier.optionId, modifier.quantity])),
  )
  const [quantity, setQuantity] = useState(initial.quantity)
  const [note, setNote] = useState(initial.note)
  // One discount per sale, so at most one on the line.
  const [discountId, setDiscountId] = useState<number | null>(initial.discounts[0]?.id ?? null)

  const picked = (group: ModifierGroup) => group.options.reduce((sum, option) => sum + (picks[option.id] ?? 0), 0)
  const complete = groups.every((group) => picked(group) === group.chooseExactly)

  function changePick(optionId: number, delta: number) {
    setPicks((current) => {
      const next = { ...current, [optionId]: (current[optionId] ?? 0) + delta }
      if (next[optionId] <= 0) delete next[optionId]
      return next
    })
  }

  const modifiers: CartModifier[] = groups.flatMap((group) =>
    group.options
      .filter((option) => picks[option.id])
      .map((option) => ({ optionId: option.id, name: option.name, quantity: picks[option.id], priceSen: toSen(option.price) })),
  )
  const line: LineDraft = {
    ...initial,
    modifiers: groups.length > 0 ? modifiers : initial.modifiers,
    quantity,
    note,
    // A discount no longer offered stays as it was.
    discounts: [...offeredDiscounts, ...initial.discounts].filter((discount) => discount.id === discountId).slice(0, 1),
  }

  return (
    <Dialog title={initial.name} wide onClose={onCancel}>
      <div className="grid gap-4">
        <div className="flex items-center justify-between rounded-2xl bg-linear-to-r from-zinc-900 to-zinc-800 px-4 py-3 text-white shadow-inner">
          <span className="text-xs font-semibold tracking-wider text-zinc-400 uppercase">Unit Price</span>
          <strong className="text-lg font-bold tabular-nums">{formatRM(initial.unitPriceSen)}</strong>
        </div>

        {groups.map((group) => {
          const count = picked(group)
          return (
            <section key={group.id} className={section} aria-label={group.name}>
              <h3>
                {group.name} <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-[0.65rem] text-zinc-500">Choose {group.chooseExactly}</span>
                <span className={`ml-auto rounded-full px-2 py-0.5 tabular-nums ${count === group.chooseExactly ? 'bg-green-50 text-green-700' : 'bg-orange-50 text-orange-700'}`}>
                  {count} / {group.chooseExactly}
                </span>
              </h3>
              <ul className="grid list-none grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-2">
                {group.options.map((option) => {
                  const quantityPicked = picks[option.id] ?? 0
                  return (
                    <li
                      key={option.id}
                      className={`flex items-center justify-between gap-2 rounded-xl py-1.5 pr-1.5 pl-3 text-sm font-medium ring-1 transition ring-inset ${
                        quantityPicked > 0 ? 'bg-zinc-800 text-white ring-zinc-800' : 'bg-white text-zinc-700 ring-zinc-200'}`}
                    >
                      <span>
                        {option.name}
                        {option.price > 0 && <span className={`text-xs ${quantityPicked > 0 ? 'text-zinc-300' : 'text-zinc-500'}`}> +{formatRM(toSen(option.price))}</span>}
                      </span>
                      <span className={stepper}>
                        <button type="button" aria-label={`One less ${option.name}`} disabled={quantityPicked === 0} onClick={() => changePick(option.id, -1)}>
                          −
                        </button>
                        <span aria-label={`${option.name} quantity`}>{quantityPicked}</span>
                        <button type="button" aria-label={`One more ${option.name}`} disabled={count >= group.chooseExactly} onClick={() => changePick(option.id, 1)}>
                          +
                        </button>
                      </span>
                    </li>
                  )
                })}
              </ul>
            </section>
          )
        })}

        <section className={section} aria-label="Notes and quantity">
          <h3>Notes and Quantity</h3>
          <div className="flex gap-3">
            <textarea className="min-h-12 flex-1 resize-none rounded-xl bg-white px-3 py-2 text-sm shadow-xs ring-1 ring-zinc-200 ring-inset placeholder:text-zinc-400 focus:ring-2 focus:ring-indigo-500 focus:outline-none" placeholder="Add a note…" aria-label="Line note" maxLength={200} value={note} onChange={(event) => setNote(event.target.value)} />
            <span className={`${stepper} [&_button]:size-12!`}>
              <button type="button" aria-label="Decrease quantity" disabled={quantity <= 1} onClick={() => setQuantity(quantity - 1)}>
                −
              </button>
              <span aria-label="Quantity">{quantity}</span>
              <button type="button" aria-label="Increase quantity" disabled={quantity >= 999} onClick={() => setQuantity(quantity + 1)}>
                +
              </button>
            </span>
          </div>
        </section>

        {offeredDiscounts.length > 0 && !initial.promotion && (
          <section className={section} aria-label="Discounts">
            <h3>
              Discounts <span className="rounded-full bg-zinc-100 px-2 py-0.5 text-[0.65rem] text-zinc-500">Choose 1</span>
            </h3>
            {discountElsewhere && (
              <p className="mb-2 rounded-xl bg-orange-50 px-3 py-2 text-sm text-orange-800 ring-1 ring-orange-200 ring-inset" role="note">
                1 discount per sale
              </p>
            )}
            <div className="grid grid-cols-[repeat(auto-fill,minmax(15rem,1fr))] gap-2">
              {offeredDiscounts.map((discount) => (
                <button
                  key={discount.id}
                  type="button"
                  className="flex items-center justify-between gap-2 rounded-xl bg-white px-3 py-2.5 text-left text-sm font-medium text-zinc-700 ring-1 ring-zinc-200 transition ring-inset enabled:hover:ring-indigo-300 aria-pressed:bg-indigo-600 aria-pressed:text-white aria-pressed:shadow-md aria-pressed:shadow-indigo-600/25 aria-pressed:ring-indigo-600"
                  aria-pressed={discountId === discount.id}
                  disabled={discountElsewhere}
                  onClick={() => setDiscountId(discountId === discount.id ? null : discount.id)}
                >
                  <span>{discount.name}</span>
                  <span className="font-semibold tabular-nums">{formatDiscount(discount)}</span>
                </button>
              ))}
            </div>
          </section>
        )}
      </div>

      <div className={`${dialogActions} sticky -bottom-6 -mx-6 mt-5! -mb-6 border-t border-zinc-100 bg-white px-6 py-4 max-md:-bottom-5 max-md:-mx-5 max-md:-mb-5 max-md:px-5`}>
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        {mode === 'edit' && onRemove && (
          <button type="button" className="btn btn-danger" onClick={onRemove}>
            Remove
          </button>
        )}
        <span className="ml-auto text-xl font-bold tracking-tight tabular-nums" data-testid="item-total">
          {formatRM(lineTotalSen(line))}
        </span>
        <button type="button" className="btn btn-primary" disabled={!complete} onClick={() => onSave(line)}>
          {mode === 'add' ? 'Add' : 'Save'}
        </button>
      </div>
    </Dialog>
  )
}
