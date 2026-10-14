import { useState, type FormEvent } from 'react'
import { ApiError, getJson } from '../../api/http'
import { Dialog } from '../../components/Dialog'
import { formatDate } from '../../lib/dates'
import { formatRM, toSen } from '../../lib/money'
import type { DiscountRule } from './pricing'
import { dialogActions } from '../../styles/ui'

/** A physical voucher as the server sees it (mirrors VoucherService.VoucherView). */
interface VoucherView {
  code: string
  name: string
  value: number
  expiresOn: string
  status: 'UNUSED' | 'USED' | 'EXPIRED' | 'CANCELLED'
}

const STATUS_TEXT: Record<Exclude<VoucherView['status'], 'UNUSED'>, string> = {
  USED: 'has already been used',
  EXPIRED: 'has expired',
  CANCELLED: 'has been cancelled',
}

interface VoucherDialogProps {
  /** The voucher becomes the sale's one discount. */
  onApply: (discount: DiscountRule) => void
  onCancel: () => void
}

/** The cashier keys in the code printed on the customer's voucher. */
export function VoucherDialog({ onApply, onCancel }: VoucherDialogProps) {
  const [code, setCode] = useState('')
  const [voucher, setVoucher] = useState<VoucherView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [checking, setChecking] = useState(false)

  async function check(event: FormEvent) {
    event.preventDefault()
    setChecking(true)
    setError(null)
    setVoucher(null)
    try {
      setVoucher(await getJson<VoucherView>(`/api/v1/vouchers/${encodeURIComponent(code.trim())}`))
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Could not reach the server. Try again.')
    } finally {
      setChecking(false)
    }
  }

  function apply() {
    onApply({ id: -1, name: `${voucher!.name} · ${voucher!.code}`, kind: 'AMOUNT', value: voucher!.value, voucherCode: voucher!.code })
  }

  return (
    <Dialog title="Voucher">
      <form className="flex items-end gap-2" onSubmit={(event) => void check(event)}>
        <label className="flex flex-1 flex-col gap-1.5 text-sm font-medium text-zinc-700">
          Voucher code
          <input
            className="rounded-xl bg-zinc-50 px-4 py-3 font-mono text-lg font-semibold tracking-[0.2em] text-zinc-900 uppercase ring-1 ring-zinc-200 transition ring-inset focus:bg-white focus:ring-2 focus:ring-indigo-500 focus:outline-none"
            autoFocus
            autoCapitalize="characters"
            autoComplete="off"
            maxLength={20}
            value={code}
            onChange={(event) => {
              setCode(event.target.value)
              setVoucher(null)
            }}
          />
        </label>
        <button type="submit" className="btn py-3.5" disabled={code.trim().length < 4 || checking}>
          {checking ? 'Checking…' : 'Check'}
        </button>
      </form>
      {error && <p className="mt-3 rounded-xl bg-red-50 px-3 py-2 text-sm font-medium text-red-700 ring-1 ring-red-200 ring-inset" role="alert">{error}</p>}
      {voucher && (
        <p className={`mt-3 rounded-xl px-3 py-2 text-sm font-medium ring-1 ring-inset ${voucher.status === 'UNUSED' ? 'bg-green-50 text-green-800 ring-green-200' : 'bg-red-50 text-red-700 ring-red-200'}`} role="status">
          {voucher.name} ({formatRM(toSen(voucher.value))} off) · valid until {formatDate(`${voucher.expiresOn}T00:00:00`)}
          {voucher.status !== 'UNUSED' && <> · this voucher {STATUS_TEXT[voucher.status]}</>}
        </p>
      )}
      <div className={dialogActions}>
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        <button type="button" className="btn btn-primary" disabled={voucher?.status !== 'UNUSED'} onClick={apply}>
          Apply voucher
        </button>
      </div>
    </Dialog>
  )
}
