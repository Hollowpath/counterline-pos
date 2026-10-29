import { useState, type FormEvent, type ReactNode } from 'react'
import { ApiError, pinLockout } from '../../api/http'
import { PinEntry } from '../../auth/PinEntry'
import { pinLockStatus } from '../../auth/pinLock'
import { Dialog } from '../../components/Dialog'
import { requestSpecialAccess } from './ordersApi'
import { dialogActions } from '../../styles/ui'

interface ManagerActionDialogProps {
  title: string
  confirmLabel: string
  /** What will happen, e.g. the amount to be refunded. */
  description: ReactNode
  /** False when the logged-in staff member is already a manager. */
  needsApproval: boolean
  /** Performs the action; `managerToken` is set when a manager approved at this terminal. */
  onConfirm: (reason: string, managerToken?: string) => Promise<void>
  onCancel: () => void
}

/**
 * A manager-only action with a mandatory reason. For a cashier, a manager first enters their PIN
 * ("Special Access Required"); the action is then recorded as theirs.
 */
export function ManagerActionDialog({ title, confirmLabel, description, needsApproval, onConfirm, onCancel }: ManagerActionDialogProps) {
  const [approval, setApproval] = useState<{ token: string; managerName: string } | null>(null)
  const [reason, setReason] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function approve(pin: string) {
    try {
      const response = await requestSpecialAccess(pin)
      setApproval({ token: response.token, managerName: response.staff.fullName })
    } catch (caught) {
      return pinLockout(caught) ?? (caught instanceof ApiError ? caught.message : 'Offline')
    }
  }

  async function confirm(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await onConfirm(reason.trim(), approval?.token)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Offline')
      setSubmitting(false)
    }
  }

  if (needsApproval && !approval) {
    return (
      <Dialog title="Special Access Required" onClose={onCancel}>
        <p className="mb-5 rounded-xl bg-orange-50 px-3 py-2.5 text-sm text-orange-800 ring-1 ring-orange-200 ring-inset">Manager PIN</p>
        <PinEntry onSubmit={approve} lockStatus={tillLockStatus} />
        <div className={dialogActions}>
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
        </div>
      </Dialog>
    )
  }

  return (
    <Dialog title={title} onClose={submitting ? undefined : onCancel}>
      <div className="text-sm text-zinc-600 [&_p]:mb-3">{description}</div>
      {approval && <p className="mb-4 inline-flex items-center gap-1.5 rounded-full bg-green-50 px-3 py-1 text-xs font-semibold text-green-700 ring-1 ring-green-200 ring-inset before:content-['✓']">Approved by {approval.managerName}</p>}
      <form onSubmit={confirm}>
        <label className="grid gap-1.5 text-sm font-medium text-zinc-700">
          Reason
          <textarea className="min-h-20 resize-none rounded-xl bg-white px-3.5 py-2.5 text-sm text-zinc-900 shadow-xs ring-1 ring-zinc-200 ring-inset focus:ring-2 focus:ring-indigo-500 focus:outline-none" value={reason} maxLength={200} required disabled={submitting} onChange={(event) => setReason(event.target.value)} />
        </label>
        {error && (
          <p className="mt-3 text-sm font-medium text-red-600" role="alert">
            {error}
          </p>
        )}
        <div className={dialogActions}>
          <button type="button" className="btn" onClick={onCancel} disabled={submitting}>
            Cancel
          </button>
          <button type="submit" className="btn btn-danger" disabled={submitting || reason.trim() === ''}>
            {submitting ? 'Saving…' : confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  )
}

/** Manager approvals share the till's PIN login lock. */
const tillLockStatus = pinLockStatus.terminal('TILL')
