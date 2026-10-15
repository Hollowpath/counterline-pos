import { useState, type FormEvent } from 'react'
import { ApiError, postJson } from '../api/http'
import { useApi } from '../api/useApi'
import { LoadState } from '../components/LoadState'
import { formatDate, formatDateTime, todayIso } from '../lib/dates'
import { formatRM, toSen } from '../lib/money'
import { field, note, sectionTitle, table, tableFrame } from './styles'
import { dashboardPaths } from './types'
import { Page } from './ui'

interface BatchSummary {
  id: number
  name: string
  value: number
  expiresOn: string
  issued: number
  used: number
  cancelled: number
  createdBy: string
  createdAt: string
}

interface VoucherRow {
  code: string
  status: 'UNUSED' | 'USED' | 'EXPIRED' | 'CANCELLED'
  usedAt: string | null
}

const day = (iso: string) => formatDate(`${iso}T00:00:00`)

/** A voucher code, easy to read out and type. */
const code = 'font-mono tracking-[0.08em]'

/**
 * Head office: physical vouchers. Issue a batch (value, expiry, how many), print its codes, and see
 * which were used. Each voucher is used once, as the sale's one discount.
 */
export function VouchersAdmin() {
  const batches = useApi<BatchSummary[]>(dashboardPaths.vouchers)
  const [open, setOpen] = useState<BatchSummary | null>(null)
  const [printed, setPrinted] = useState<{ name: string; value: number; expiresOn: string; codes: string[] } | null>(null)

  if (printed) {
    return <PrintCodes {...printed} onDone={() => setPrinted(null)} />
  }
  if (open) {
    return <BatchCodes batch={open} onBack={() => setOpen(null)} onPrint={(codes) => setPrinted({ ...open, codes })} />
  }
  return (
    <Page title="Vouchers" description="Printed vouchers, each used once before it expires. At the till: % tab → Voucher.">
      <IssueForm onIssued={(batch, codes) => {
        batches.reload()
        setPrinted({ name: batch.name, value: batch.value, expiresOn: batch.expiresOn, codes })
      }} />
      {batches.status !== 'success' ? (
        <LoadState status={batches.status} message={batches.status === 'error' ? batches.message : undefined} onRetry={batches.reload} />
      ) : batches.data.length === 0 ? (
        <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No vouchers issued yet.</p>
      ) : (
        <section aria-label="Issued vouchers">
          <h2 className={sectionTitle}>Issued</h2>
          <div className={tableFrame}>
            <table className={table}>
              <thead>
                <tr>
                  <th>Batch</th>
                  <th>Value</th>
                  <th>Valid until</th>
                  <th>Used</th>
                  <th>Issued by</th>
                  <th aria-label="Open" />
                </tr>
              </thead>
              <tbody>
                {batches.data.map((batch) => (
                  <tr key={batch.id}>
                    <td className="font-semibold">{batch.name}</td>
                    <td>{formatRM(toSen(batch.value))}</td>
                    <td>{day(batch.expiresOn)}</td>
                    <td>{batch.used} of {batch.issued}</td>
                    <td>
                      {batch.createdBy}
                      <span className={note}>{formatDateTime(batch.createdAt)}</span>
                    </td>
                    <td className="text-right">
                      <button type="button" className="btn" onClick={() => setOpen(batch)}>
                        Codes
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </Page>
  )
}

function IssueForm({ onIssued }: { onIssued: (batch: BatchSummary, codes: string[]) => void }) {
  const [name, setName] = useState('RM10 Voucher')
  const [value, setValue] = useState('10')
  const [expiresOn, setExpiresOn] = useState('')
  const [quantity, setQuantity] = useState('50')
  const [error, setError] = useState<string | null>(null)
  const [sending, setSending] = useState(false)

  async function issue(event: FormEvent) {
    event.preventDefault()
    setSending(true)
    setError(null)
    try {
      const created = await postJson<{ batch: BatchSummary; codes: string[] }>(dashboardPaths.vouchers, {
        name: name.trim(), value: Number(value), expiresOn, quantity: Number(quantity),
      })
      onIssued(created.batch, created.codes)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Offline')
    } finally {
      setSending(false)
    }
  }

  const label = 'flex flex-col gap-1 text-[0.85rem] text-zinc-500'
  return (
    <form className="rounded-2xl bg-white p-5 shadow-sm ring-1 ring-zinc-900/5" aria-label="Issue vouchers" onSubmit={(event) => void issue(event)}>
      <h2 className={sectionTitle}>Issue vouchers</h2>
      <div className="flex flex-wrap items-end gap-3">
        <label className={label}>
          Name
          <input className={field} required maxLength={50} value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <label className={label}>
          Value (RM)
          <input className={`${field} w-28`} required type="number" min="0.01" step="0.01" value={value}
            onChange={(event) => setValue(event.target.value)} />
        </label>
        <label className={label}>
          Valid until
          <input className={field} required type="date" min={todayIso()} value={expiresOn} onChange={(event) => setExpiresOn(event.target.value)} />
        </label>
        <label className={label}>
          How many
          <input className={`${field} w-24`} required type="number" min="1" max="500" value={quantity}
            onChange={(event) => setQuantity(event.target.value)} />
        </label>
        <button type="submit" className="btn btn-primary py-2" disabled={sending}>
          {sending ? 'Issuing…' : 'Issue vouchers'}
        </button>
      </div>
      {error && <p className="mt-3 text-sm font-medium text-red-600" role="alert">{error}</p>}
    </form>
  )
}

function BatchCodes({ batch, onBack, onPrint }: { batch: BatchSummary; onBack: () => void; onPrint: (codes: string[]) => void }) {
  const vouchers = useApi<VoucherRow[]>(dashboardPaths.voucherBatch(batch.id))
  return (
    <Page
      label={batch.name}
      title={batch.name}
      description={`${formatRM(toSen(batch.value))} off · valid until ${day(batch.expiresOn)} · ${batch.used} of ${batch.issued} used`}
      actions={
        <>
          <button type="button" className="btn" onClick={onBack}>
            ‹ Vouchers
          </button>
          {vouchers.status === 'success' && (
            <button type="button" className="btn btn-primary"
              onClick={() => onPrint(vouchers.data.filter((v) => v.status === 'UNUSED').map((v) => v.code))}>
              Print unused codes
            </button>
          )}
        </>
      }
    >
      {vouchers.status !== 'success' ? (
        <LoadState status={vouchers.status} message={vouchers.status === 'error' ? vouchers.message : undefined} onRetry={vouchers.reload} />
      ) : (
        <div className={tableFrame}>
          <table className={table}>
            <thead>
              <tr>
                <th>Code</th>
                <th>Status</th>
                <th>Used</th>
              </tr>
            </thead>
            <tbody>
              {vouchers.data.map((voucher) => (
                <tr key={voucher.code} className={voucher.status === 'UNUSED' ? undefined : 'opacity-55'}>
                  <td className={code}>{voucher.code}</td>
                  <td>{voucher.status.charAt(0) + voucher.status.slice(1).toLowerCase()}</td>
                  <td>{voucher.usedAt ? formatDateTime(voucher.usedAt) : '–'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  )
}

/** The codes laid out to print and cut into vouchers. */
function PrintCodes({ name, value, expiresOn, codes, onDone }: {
  name: string; value: number; expiresOn: string; codes: string[]; onDone: () => void
}) {
  return (
    <section aria-label="Voucher codes to print" className="p-6 max-md:p-4 print:p-0">
      <div className="mb-4 flex items-center gap-3 print:hidden">
        <button type="button" className="btn" onClick={onDone}>
          ‹ Vouchers
        </button>
        <button type="button" className="btn btn-primary" onClick={() => window.print()}>
          Print
        </button>
        <span className="text-sm text-zinc-500">{codes.length} codes</span>
      </div>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(14rem,1fr))] gap-3">
        {codes.map((voucherCode) => (
          <div key={voucherCode}
            className="relative flex break-inside-avoid flex-col gap-1 overflow-hidden rounded-2xl border-2 border-dashed border-indigo-200 bg-linear-to-br from-white to-indigo-50/60 p-4 print:border-black print:bg-none">
            <strong>{name}</strong>
            <span className="text-[1.5rem] font-black tracking-tight text-indigo-700 print:text-black">{formatRM(toSen(value))} off</span>
            <span className={code}>{voucherCode}</span>
            <span className="text-xs text-zinc-500">Valid until {day(expiresOn)} · one use</span>
          </div>
        ))}
      </div>
    </section>
  )
}
