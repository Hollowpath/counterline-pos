import { useState, type ReactNode } from 'react'
import { usePagedList } from '../../api/usePagedList'
import { AppBar } from '../../app/AppBar'
import { LoadState } from '../../components/LoadState'
import { maskContact } from '../../lib/privacy'
import { useDebouncedValue } from '../../lib/useDebouncedValue'
import { CustomerDetail } from './CustomerDetail'
import { CustomerForm } from './CustomerForm'
import { customerPaths, type CustomerSummary, type SearchField } from './types'
import { appBarTitle, customerRow, rowList, segmented, splitScreen } from '../../styles/ui'

const SEARCH_FIELDS: { field: SearchField; label: string }[] = [
  { field: 'REFERENCE', label: 'Reference' },
  { field: 'NAME', label: 'Name' },
  { field: 'PHONE', label: 'Phone' },
  { field: 'EMAIL', label: 'Email' },
]

/** Customers: search on the left (by reference, name, phone or email), the selected customer on the right. */
interface CustomersScreenProps {
  /** The POS's bottom bar; absent on the dashboard, which has its own navigation. */
  onOpenMenu?: () => void
  /** More about the selected customer (the dashboard's points and rewards). */
  renderExtra?: (customerId: number) => ReactNode
}

export function CustomersScreen({ onOpenMenu, renderExtra }: CustomersScreenProps) {
  const [field, setField] = useState<SearchField>('NAME')
  const [searchInput, setSearchInput] = useState('')
  const query = useDebouncedValue(searchInput.trim(), 300)
  const { state, hasMore, loadMore, refresh } = usePagedList<CustomerSummary>((page) => customerPaths.search(field, query, page))
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [adding, setAdding] = useState(false)

  return (
    <div className={splitScreen.screen} data-detail={selectedId !== null}>
      <section className={splitScreen.list} aria-label="Customers">
        <div className="flex items-center gap-2">
          <div className={`${segmented} flex-1`} role="tablist" aria-label="Search by">
            {SEARCH_FIELDS.map((option) => (
              <button
                key={option.field}
                type="button"
                role="tab"
                aria-selected={option.field === field}
                onClick={() => setField(option.field)}
              >
                {option.label}
              </button>
            ))}
          </div>
          <button type="button" className="icon-btn" aria-label="Add customer" onClick={() => setAdding(true)}>
            +
          </button>
        </div>
        <input
          className={splitScreen.search}
          type="search"
          placeholder={`Search by ${SEARCH_FIELDS.find((option) => option.field === field)!.label.toLowerCase()}`}
          aria-label="Search customers"
          value={searchInput}
          maxLength={60}
          onChange={(event) => setSearchInput(event.target.value)}
        />

        {state.phase !== 'ready' ? (
          <LoadState status={state.phase} message={state.phase === 'error' ? state.message : undefined} onRetry={refresh} />
        ) : state.items.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">{query ? 'No customers match your search.' : 'No customers yet.'}</p>
        ) : (
          <>
            <ul className={rowList}>
              {state.items.map((customer) => (
                <li key={customer.id}>
                  <button
                    type="button"
                    className={customerRow.button}
                    aria-current={customer.id === selectedId ? 'true' : undefined}
                    onClick={() => setSelectedId(customer.id)}
                  >
                    <span className={customerRow.name}>{customer.fullName}</span>
                    <span className={customerRow.contact}>{maskContact(customer.phone, customer.email)}</span>
                  </button>
                </li>
              ))}
            </ul>
            {hasMore && (
              <button type="button" className={`btn ${splitScreen.more}`} disabled={state.loadingMore} onClick={() => void loadMore()}>
                {state.loadingMore ? 'Loading…' : 'Load more'}
              </button>
            )}
          </>
        )}
      </section>

      <section className={splitScreen.detail} aria-label="Customer detail">
        {selectedId === null ? (
          <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">Select a customer</p>
        ) : (
          <>
            <button type="button" className={`btn ${splitScreen.back}`} onClick={() => setSelectedId(null)}>
              ‹ Customers
            </button>
            <CustomerDetail
              key={selectedId}
              customerId={selectedId}
              extra={renderExtra?.(selectedId)}
              onChanged={(deleted) => {
                if (deleted) setSelectedId(null)
                refresh()
              }}
            />
          </>
        )}
      </section>

      {adding && (
        <CustomerForm
          onSaved={(created) => {
            setAdding(false)
            setSelectedId(created.id)
            refresh()
          }}
          onCancel={() => setAdding(false)}
        />
      )}

      {onOpenMenu && (
        <AppBar label="Customers" onOpenMenu={onOpenMenu}>
          <p className={appBarTitle}>Customers</p>
        </AppBar>
      )}
    </div>
  )
}
