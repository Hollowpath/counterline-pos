import { useState } from 'react'
import { useApi } from '../../api/useApi'
import { LoadState } from '../../components/LoadState'
import { catalogPaths } from '../catalog/types'
import { formatDiscount, type DiscountRule } from './pricing'
import { VoucherDialog } from './VoucherDialog'
import { discountTile, tileGrid, tilePrice } from './styles'

interface OrderDiscountsGridProps {
  selected: DiscountRule[]
  /** A line discount already in the sale, if any: a sale takes only one discount. */
  lineDiscount: string | null
  onChange: (discounts: DiscountRule[]) => void
}

/** The % tab: one discount on the whole order, toggled on and off, or a customer's voucher. */
export function OrderDiscountsGrid({ selected, lineDiscount, onChange }: OrderDiscountsGridProps) {
  const offered = useApi<DiscountRule[]>(catalogPaths.discounts('ORDER'))
  const [keyingVoucher, setKeyingVoucher] = useState(false)
  const voucher = selected.find((discount) => discount.voucherCode)
  if (offered.status !== 'success') {
    return <LoadState status={offered.status} message={offered.status === 'error' ? offered.message : undefined} onRetry={offered.reload} />
  }

  function toggle(discount: DiscountRule) {
    onChange(selected.some((d) => d.id === discount.id) ? [] : [discount])
  }

  return (
    <>
      {lineDiscount && (
        <p className="mb-4 rounded-2xl bg-orange-50 px-4 py-3 text-center text-sm font-medium text-orange-800 ring-1 ring-orange-200 ring-inset" role="note">
          This sale already has {lineDiscount}. Only one discount can be applied to a sale.
        </p>
      )}
      <div className={tileGrid}>
        <button
          type="button"
          className={discountTile}
          aria-pressed={voucher !== undefined}
          disabled={lineDiscount !== null || (selected.length > 0 && voucher === undefined)}
          onClick={() => (voucher ? onChange([]) : setKeyingVoucher(true))}
        >
          <span>{voucher ? voucher.name : 'Voucher'}</span>
          <span className={tilePrice}>{voucher ? formatDiscount(voucher) : 'Key in code'}</span>
        </button>
        {offered.data.map((discount) => (
          <button
            key={discount.id}
            type="button"
            className={discountTile}
            aria-pressed={selected.some((d) => d.id === discount.id)}
            disabled={lineDiscount !== null || voucher !== undefined}
            onClick={() => toggle(discount)}
          >
            <span>{discount.name}</span>
            <span className={tilePrice}>{formatDiscount(discount)}</span>
          </button>
        ))}
      </div>
      {keyingVoucher && (
        <VoucherDialog
          onApply={(discount) => {
            onChange([discount])
            setKeyingVoucher(false)
          }}
          onCancel={() => setKeyingVoucher(false)}
        />
      )}
    </>
  )
}
