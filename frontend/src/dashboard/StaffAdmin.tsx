import { useState, type FormEvent } from 'react'
import { deleteJson, postJson, putJson } from '../api/http'
import { useApi } from '../api/useApi'
import { useConfirm } from '../components/confirmContext'
import { LoadState } from '../components/LoadState'
import { message } from './catalogHelpers'
import { field, note, noticeClass, sectionTitle, table, tableAsCards, tableFrame } from './styles'
import { dashboardPaths, type AdminOutlet, type AdminStaff, type Notice } from './types'
import { Page } from './ui'

type Role = AdminStaff['role']

const ROLES: { value: Role; label: string }[] = [
  { value: 'STAFF', label: 'Cashier' },
  { value: 'MANAGER', label: 'Manager' },
]

/**
 * Head office's staff: cashiers and managers per outlet. Staff are never deleted (their sales and
 * timecards keep their name); a deactivated person can no longer sign in. A PIN is typed once and
 * never shown again; only managers have a dashboard e-mail and password.
 */
export function StaffAdmin() {
  const outlets = useApi<AdminOutlet[]>(dashboardPaths.adminOutlets)
  const [outletId, setOutletId] = useState<number | null>(null)
  const staff = useApi<AdminStaff[]>(dashboardPaths.adminStaff(outletId))
  const [notice, setNotice] = useState<Notice | null>(null)

  if (outlets.status !== 'success') {
    return <LoadState status={outlets.status} message={outlets.status === 'error' ? outlets.message : undefined} onRetry={outlets.reload} />
  }
  const openOutlets = outlets.data.filter((outlet) => outlet.active)
  const outletName = (id: number) => outlets.data.find((outlet) => outlet.id === id)?.name ?? `Outlet ${id}`

  function changed(text: string) {
    setNotice({ tone: 'ok', text })
    staff.reload()
  }
  const failed = (text: string) => setNotice({ tone: 'error', text })

  return (
    <Page
      title="Staff"
      description="Cashiers and managers. A PIN is set once and never shown again. Deactivating someone stops them signing in; their past sales stay under their name."
      actions={
        <label className="flex items-center gap-2 text-sm font-medium text-zinc-700">
          Outlet
          <select className={field} value={outletId ?? ''} onChange={(event) => setOutletId(event.target.value === '' ? null : Number(event.target.value))}>
            <option value="">All outlets</option>
            {outlets.data.map((outlet) => (
              <option key={outlet.id} value={outlet.id}>{outlet.code} · {outlet.name}{outlet.active ? '' : ' (closed)'}</option>
            ))}
          </select>
        </label>
      }
    >
      {notice && <p className={noticeClass(notice.tone)} role="status">{notice.text}</p>}
      <NewStaffForm outlets={openOutlets} defaultOutletId={outletId} onAdded={(added) => changed(`Added ${added.fullName}`)} onError={failed} />
      <section aria-label="Staff list">
        <h2 className={sectionTitle}>{outletId === null ? 'All staff' : `Staff at ${outletName(outletId)}`}</h2>
        {staff.status !== 'success' ? (
          <LoadState status={staff.status} message={staff.status === 'error' ? staff.message : undefined} onRetry={staff.reload} />
        ) : staff.data.length === 0 ? (
          <p className="rounded-2xl border border-dashed border-zinc-300 p-8 text-center text-sm text-zinc-500">No staff yet.</p>
        ) : (
          <div className={tableFrame}>
            <table className={`${table} ${tableAsCards}`}>
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Role</th>
                  <th>Outlet</th>
                  <th>Status</th>
                  <th aria-label="Actions" />
                </tr>
              </thead>
              <tbody>
                {staff.data.map((member) => (
                  <StaffRow key={`${member.id}-${member.fullName}-${member.role}-${member.outletId}-${member.active}-${member.email}`} member={member}
                    outlets={outlets.data} onChanged={changed} onError={failed} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </Page>
  )
}

function NewStaffForm({ outlets, defaultOutletId, onAdded, onError }: {
  outlets: AdminOutlet[]
  defaultOutletId: number | null
  onAdded: (staff: AdminStaff) => void
  onError: (text: string) => void
}) {
  const [fullName, setFullName] = useState('')
  const [role, setRole] = useState<Role>('STAFF')
  const [chosenOutlet, setChosenOutlet] = useState<number | null>(null)
  const [pin, setPin] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const outletId = chosenOutlet ?? defaultOutletId ?? outlets[0]?.id ?? null

  async function add(event: FormEvent) {
    event.preventDefault()
    if (outletId === null) return
    setBusy(true)
    try {
      const login = role === 'MANAGER' && (email.trim() !== '' || password !== '') ? { email, password } : {}
      const added = await postJson<AdminStaff>(dashboardPaths.adminStaff(null), { fullName, role, outletId, pin, ...login })
      setFullName('')
      setPin('')
      setEmail('')
      setPassword('')
      onAdded(added)
    } catch (error) {
      // The form keeps what was typed, so it can be corrected.
      onError(message(error))
    } finally {
      setBusy(false)
    }
  }

  const label = 'flex flex-col gap-1 text-sm font-medium text-zinc-700'
  return (
    <form aria-label="Add a staff member" className="flex flex-wrap items-end gap-3 rounded-2xl bg-white p-4 shadow-sm ring-1 ring-zinc-900/5" onSubmit={(event) => void add(event)}>
      <label className={label}>
        Name
        <input className={field} required maxLength={120} value={fullName} onChange={(event) => setFullName(event.target.value)} />
      </label>
      <label className={label}>
        Role
        <select className={field} value={role} onChange={(event) => setRole(event.target.value as Role)}>
          {ROLES.map((entry) => <option key={entry.value} value={entry.value}>{entry.label}</option>)}
        </select>
      </label>
      <label className={label}>
        Works at
        <select className={field} value={outletId ?? ''} onChange={(event) => setChosenOutlet(Number(event.target.value))}>
          {outlets.map((outlet) => <option key={outlet.id} value={outlet.id}>{outlet.code} · {outlet.name}</option>)}
        </select>
      </label>
      <label className={label}>
        PIN (6 digits)
        <input className={`${field} w-32`} required type="password" inputMode="numeric" autoComplete="off" pattern="\d{6}" maxLength={6}
          value={pin} onChange={(event) => setPin(event.target.value)} />
      </label>
      {role === 'MANAGER' && (
        <>
          <label className={label}>
            Dashboard e-mail (optional)
            <input className={field} type="email" autoComplete="off" value={email} onChange={(event) => setEmail(event.target.value)} />
          </label>
          <label className={label}>
            Dashboard password
            <input className={field} type="password" autoComplete="new-password" minLength={12} value={password}
              required={email.trim() !== ''} onChange={(event) => setPassword(event.target.value)} />
            <span className={note}>At least 12 characters</span>
          </label>
        </>
      )}
      <button type="submit" className="btn btn-primary" disabled={busy || outletId === null}>Add staff member</button>
    </form>
  )
}

function StaffRow({ member, outlets, onChanged, onError }: {
  member: AdminStaff
  outlets: AdminOutlet[]
  onChanged: (text: string) => void
  onError: (text: string) => void
}) {
  const confirm = useConfirm()
  const [fullName, setFullName] = useState(member.fullName)
  const [role, setRole] = useState<Role>(member.role)
  const [outletId, setOutletId] = useState(member.outletId)
  const [newPin, setNewPin] = useState<string | null>(null)
  const [loginOpen, setLoginOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const dirty = fullName !== member.fullName || role !== member.role || outletId !== member.outletId
  const name = member.fullName

  async function run(action: () => Promise<unknown>, done: string) {
    setBusy(true)
    try {
      await action()
      onChanged(done)
    } catch (error) {
      onError(message(error))
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    if (member.role === 'MANAGER' && role === 'STAFF' && member.email !== null
      && !await confirm({ title: `Make ${name} a cashier?`, message: 'A cashier has no dashboard login, so theirs is removed and any dashboard session ends. They keep their till PIN.', confirmLabel: 'Make cashier' })) return
    void run(() => putJson(dashboardPaths.adminStaffMember(member.id), { fullName, role, outletId }), `Saved ${fullName}`)
  }

  function setPin() {
    if (newPin === null) return
    void run(async () => {
      await postJson(dashboardPaths.adminStaffPin(member.id), { pin: newPin })
      setNewPin(null)
    }, `Reset the PIN of ${name}`)
  }

  return (
    <>
    <tr>
      <td>
        <input className={`${field} w-full min-w-40`} aria-label={`Name of ${name}`} value={fullName} maxLength={120} onChange={(event) => setFullName(event.target.value)} />
        {member.email && <span className={note}>Dashboard login: {member.email}</span>}
      </td>
      <td data-label="Role">
        <select className={field} aria-label={`Role of ${name}`} value={role} onChange={(event) => setRole(event.target.value as Role)}>
          {ROLES.map((entry) => <option key={entry.value} value={entry.value}>{entry.label}</option>)}
        </select>
      </td>
      <td data-label="Outlet">
        <select className={field} aria-label={`Outlet of ${name}`} value={outletId} onChange={(event) => setOutletId(Number(event.target.value))}>
          {outlets.filter((outlet) => outlet.active || outlet.id === member.outletId).map((outlet) => (
            <option key={outlet.id} value={outlet.id}>{outlet.code} · {outlet.name}{outlet.active ? '' : ' (closed)'}</option>
          ))}
        </select>
      </td>
      <td data-label="Status">{member.active ? 'Active' : 'Deactivated'}</td>
      <td className="text-right">
        <div className="flex flex-wrap items-center justify-end gap-2">
          {newPin === null ? (
            <button type="button" className="btn" disabled={busy} aria-label={`Reset PIN of ${name}`} onClick={() => setNewPin('')}>
              Reset PIN
            </button>
          ) : (
            <>
              <input className={`${field} w-28`} type="password" inputMode="numeric" autoComplete="off" maxLength={6} aria-label={`New PIN for ${name}`}
                value={newPin} onChange={(event) => setNewPin(event.target.value)} />
              <button type="button" className="btn btn-primary" disabled={busy || !/^\d{6}$/.test(newPin)} aria-label={`Set PIN of ${name}`} onClick={setPin}>
                Set PIN
              </button>
              <button type="button" className="btn" disabled={busy} onClick={() => setNewPin(null)}>Cancel</button>
            </>
          )}
          {member.role === 'MANAGER' && (
            <button type="button" className="btn" aria-expanded={loginOpen} aria-label={`Dashboard login of ${name}`} onClick={() => setLoginOpen(!loginOpen)}>
              Dashboard login
            </button>
          )}
          <button type="button" className="btn" disabled={busy || !dirty} aria-label={`Save ${name}`} onClick={() => void save()}>Save</button>
          {member.active ? (
            <button type="button" className="btn btn-danger" disabled={busy} aria-label={`Deactivate ${name}`}
              onClick={async () => {
                if (!await confirm({ title: `Deactivate ${name}?`, message: 'They can no longer sign in. Their past sales and timecards are kept, and they can be reactivated.', confirmLabel: 'Deactivate' })) return
                void run(() => postJson(dashboardPaths.adminStaffAction(member.id, 'deactivate'), {}), `Deactivated ${name}`)
              }}>
              Deactivate
            </button>
          ) : (
            <button type="button" className="btn" disabled={busy} aria-label={`Reactivate ${name}`}
              onClick={() => void run(() => postJson(dashboardPaths.adminStaffAction(member.id, 'reactivate'), {}), `Reactivated ${name}`)}>
              Reactivate
            </button>
          )}
        </div>
      </td>
    </tr>
    {loginOpen && member.role === 'MANAGER' && (
      <tr>
        <td colSpan={5}>
          <LoginPanel member={member} onChanged={onChanged} onError={onError} />
        </td>
      </tr>
    )}
    </>
  )
}

/**
 * A manager's dashboard login. Adding one needs an e-mail and a password together; once it exists
 * either can be changed alone. The stored password is never shown, and a blank password field
 * leaves it as it is. The password field is emptied after every successful save.
 */
function LoginPanel({ member, onChanged, onError }: {
  member: AdminStaff
  onChanged: (text: string) => void
  onError: (text: string) => void
}) {
  const confirm = useConfirm()
  const hasLogin = member.email !== null
  const [email, setEmail] = useState(member.email ?? '')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const name = member.fullName
  const emailChanged = email.trim() !== '' && email.trim().toLowerCase() !== member.email
  const ready = hasLogin ? emailChanged || password !== '' : email.trim() !== '' && password !== ''

  async function run(action: () => Promise<unknown>, done: string) {
    setBusy(true)
    try {
      await action()
      setPassword('')
      onChanged(done)
    } catch (error) {
      // The e-mail and password stay in the form, so they can be corrected.
      onError(message(error))
    } finally {
      setBusy(false)
    }
  }

  function save() {
    const login = { ...(emailChanged || !hasLogin ? { email: email.trim() } : {}), ...(password !== '' ? { password } : {}) }
    void run(
      () => putJson(dashboardPaths.adminStaffMember(member.id), { fullName: member.fullName, role: member.role, outletId: member.outletId, ...login }),
      hasLogin ? `Updated the dashboard login of ${name}` : `Added a dashboard login for ${name}`,
    )
  }

  const label = 'flex flex-col gap-1 text-sm font-medium text-zinc-700'
  return (
    <form aria-label={`Dashboard login of ${name}`} className="flex flex-wrap items-end gap-3" onSubmit={(event) => { event.preventDefault(); if (ready) save() }}>
      <label className={label}>
        Dashboard e-mail
        <input className={field} type="email" autoComplete="off" aria-label={`Dashboard e-mail of ${name}`} value={email}
          onChange={(event) => setEmail(event.target.value)} />
      </label>
      <label className={label}>
        {hasLogin ? 'New dashboard password' : 'Dashboard password'}
        <input className={field} type="password" autoComplete="new-password" aria-label={`Dashboard password of ${name}`} value={password}
          onChange={(event) => setPassword(event.target.value)} />
        <span className={note}>{hasLogin ? 'Leave blank to keep the current password' : 'At least 12 characters'}</span>
      </label>
      <button type="submit" className="btn btn-primary" disabled={busy || !ready} aria-label={`Save dashboard login of ${name}`}>
        {hasLogin ? 'Save login' : 'Add login'}
      </button>
      {hasLogin && (
        <button type="button" className="btn btn-danger" disabled={busy} aria-label={`Remove dashboard login of ${name}`}
          onClick={async () => {
            if (!await confirm({ title: `Remove the dashboard login of ${name}?`, message: 'Their dashboard session ends. They can still sign in at the till with their PIN.', confirmLabel: 'Remove login' })) return
            void run(() => deleteJson(dashboardPaths.adminStaffLogin(member.id)), `Removed the dashboard login of ${name}`)
          }}>
          Remove login
        </button>
      )}
    </form>
  )
}
