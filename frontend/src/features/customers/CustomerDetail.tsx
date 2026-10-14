import { useState, type ReactNode } from 'react'
import { useApi } from '../../api/useApi'
import { LoadState } from '../../components/LoadState'
import { formatDate, formatDateTime } from '../../lib/dates'
import { formatRM, toSen } from '../../lib/money'
import { maskEmail, maskPhone } from '../../lib/privacy'
import { CustomerForm } from './CustomerForm'
import { customerPaths, type Customer, type CustomerAnalytics } from './types'
import { detail } from '../../styles/ui'

interface CustomerDetailProps {
  customerId: number
  /** The customer was edited or deleted: the list should refresh. */
  onChanged: (deleted: boolean) => void
  /** Shown after the analytics (the dashboard's points and rewards). */
  extra?: ReactNode
}

export function CustomerDetail({ customerId, onChanged, extra }: CustomerDetailProps) {
  const customer = useApi<Customer>(customerPaths.customer(customerId))
  const analytics = useApi<CustomerAnalytics>(customerPaths.analytics(customerId))
  const [editing, setEditing] = useState(false)

  if (customer.status !== 'success') {
    return <LoadState status={customer.status} message={customer.status === 'error' ? customer.message : undefined} onRetry={customer.reload} />
  }
  const c = customer.data
  const consent = [c.marketingEmail && 'Email', c.marketingSms && 'Text (SMS)', c.marketingCall && 'Phone (Call)'].filter(Boolean)
  const address = [c.streetAddress, c.aptSuite, c.city, c.postalCode, c.country].filter(Boolean).join(', ')

  return (
    <article className="[&_h2]:text-2xl [&_h2]:font-bold [&_h2]:tracking-tight" aria-label={c.fullName}>
      <h2>{c.fullName}</h2>

      <h3 className={detail.section}>Customer Information</h3>
      <dl className={detail.info}>
        <Row label="Phone" value={c.phone && maskPhone(c.phone)} />
        <Row label="Email" value={c.email && maskEmail(c.email)} />
        <Row label="Reference ID" value={c.referenceId} />
        <Row label="Company" value={c.companyName} />
        <Row label="Date of Birth" value={c.dateOfBirth && formatDate(c.dateOfBirth)} />
        <Row label="Gender" value={c.gender && (c.gender === 'MALE' ? 'Male' : 'Female')} />
        <Row label="Marketing Consent" value={consent.length ? consent.join(', ') : 'None'} />
        <Row label="Address" value={address} />
        <Row label="Notes" value={c.notes} />
      </dl>

      <h3 className={detail.section}>Customer Analytics</h3>
      {analytics.status !== 'success' ? (
        <LoadState status={analytics.status} message={analytics.status === 'error' ? analytics.message : undefined} onRetry={analytics.reload} />
      ) : (
        <>
          <dl className={detail.info}>
            <Row label="Order Count" value={String(analytics.data.orderCount)} />
            <Row label="Order Average" value={formatRM(toSen(analytics.data.orderAverage))} />
            <Row label="Order Total" value={formatRM(toSen(analytics.data.orderTotal))} />
            <Row label="Last Order Date" value={analytics.data.lastOrderAt ? formatDateTime(analytics.data.lastOrderAt) : '–'} />
          </dl>
          {analytics.data.mostOrderedItems.length > 0 && (
            <>
              <h3 className={detail.section}>Most Ordered Item(s)</h3>
              <ol className={`${detail.rows} [&_li]:justify-start!`}>
                {analytics.data.mostOrderedItems.map((item, index) => (
                  <li key={item.itemName}>
                    <span className="grid size-6 place-items-center rounded-full bg-indigo-50 text-xs font-bold text-indigo-600">{index + 1}</span>
                    <span>
                      {item.quantity} {item.itemName}
                    </span>
                  </li>
                ))}
              </ol>
            </>
          )}
        </>
      )}

      {extra}

      <button type="button" className="btn btn-primary mt-6 w-full py-3.5!" onClick={() => setEditing(true)}>
        Edit Customer Details
      </button>

      {editing && (
        <CustomerForm
          customer={c}
          onSaved={() => {
            setEditing(false)
            customer.reload()
            onChanged(false)
          }}
          onDeleted={() => onChanged(true)}
          onCancel={() => setEditing(false)}
        />
      )}
    </article>
  )
}

function Row({ label, value }: { label: string; value: string | null | false | undefined }) {
  if (!value) return null
  return (
    <div>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}
