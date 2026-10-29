import { useState } from 'react'
import { usePagedList } from '../../api/usePagedList'
import { Dialog } from '../../components/Dialog'
import { LoadState } from '../../components/LoadState'
import { maskContact } from '../../lib/privacy'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import type { CartCustomer } from '../sell/cart'
import { CustomerForm } from './CustomerForm'
import { customerPaths, type CustomerSummary, type SearchField } from './types'
import { customerRow, dialogActions, splitScreen } from '../../styles/ui'

/** Digits with optional +, spaces or dashes: the cashier is typing a phone number. */
const PHONE_LIKE = /^\+?[\d\s-]{3,}$/

interface CustomerPickerProps {
  onSelect: (customer: CartCustomer) => void
  onCancel: () => void
}

/** "Select a Customer" for the current sale: search by name or phone, or add a new customer. */
export function CustomerPicker({ onSelect, onCancel }: CustomerPickerProps) {
  const [input, setInput] = useState('')
  const query = useDebouncedValue(input.trim(), 300)
  const field: SearchField = PHONE_LIKE.test(query) ? 'PHONE' : 'NAME'
  const { state, hasMore } = usePagedList<CustomerSummary>((page) => customerPaths.search(field, query, page))
  const [adding, setAdding] = useState(false)

  if (adding) {
    return (
      <CustomerForm
        onSaved={(created) => onSelect({ id: created.id, name: created.fullName, phone: created.phone })}
        onCancel={() => setAdding(false)}
      />
    )
  }

  return (
    <Dialog title="Select a Customer" onClose={onCancel} closeOnBackdrop>
      <input
        className={`${splitScreen.search} w-full`}
        type="search"
        placeholder="Name or phone number"
        aria-label="Search customers"
        value={input}
        maxLength={60}
        autoFocus
        onChange={(event) => setInput(event.target.value)}
      />

      {state.phase !== 'ready' ? (
        <LoadState status={state.phase} message={state.phase === 'error' ? state.message : undefined} />
      ) : state.items.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">{query ? 'No customers match your search.' : 'No customers yet.'}</p>
      ) : (
        <ul className="my-3 flex max-h-[50vh] list-none flex-col gap-1 overflow-y-auto">
          {state.items.map((customer) => (
            <li key={customer.id}>
              <button
                type="button"
                className={customerRow.button}
                onClick={() => onSelect({ id: customer.id, name: customer.fullName, phone: customer.phone })}
              >
                <span className={customerRow.name}>{customer.fullName}</span>
                <span className={customerRow.contact}>{maskContact(customer.phone, customer.email)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {hasMore && <p className="text-xs text-zinc-500">More customers match; refine the search.</p>}

      <div className={dialogActions}>
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}>
          New customer
        </button>
      </div>
    </Dialog>
  )
}
