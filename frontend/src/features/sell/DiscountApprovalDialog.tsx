import { ApiError, pinLockout } from '../../api/http'
import { PinEntry } from '../../auth/PinEntry'
import { pinLockStatus } from '../../auth/pinLock'
import { Dialog } from '../../components/Dialog'
import { requestSpecialAccess } from '../orders/ordersApi'
import { dialogActions } from '../../styles/ui'

interface DiscountApprovalDialogProps {
  /** The discount being approved, e.g. "Marketing Department Special Approved". */
  discountName: string
  /** `token`: the manager's short-lived special-access token, good for one completed sale. */
  onApproved: (token: string) => void
  onCancel: () => void
}

/** "Special Access Required" for a department comp: a manager enters their PIN at this terminal. */
export function DiscountApprovalDialog({ discountName, onApproved, onCancel }: DiscountApprovalDialogProps) {
  async function approve(pin: string) {
    try {
      const response = await requestSpecialAccess(pin, 'DISCOUNT')
      onApproved(response.token)
    } catch (caught) {
      return pinLockout(caught) ?? (caught instanceof ApiError ? caught.message : 'Offline')
    }
  }

  return (
    <Dialog title="Special Access Required" onClose={onCancel}>
      <p className="mb-5 rounded-xl bg-orange-50 px-3 py-2.5 text-sm text-orange-800 ring-1 ring-orange-200 ring-inset">Manager PIN · <strong className="font-semibold">Discount: {discountName}</strong></p>
      <PinEntry onSubmit={approve} lockStatus={tillLockStatus} />
      <div className={dialogActions}>
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </Dialog>
  )
}

/** Manager approvals share the till's PIN login lock. */
const tillLockStatus = pinLockStatus.terminal('TILL')
