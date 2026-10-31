import { render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthToken } from '../api/http'
import { DashboardApp } from './DashboardApp'
import { answerConfirm } from '../test/confirmDialog'
import type { AdminOutlet, AdminStaff } from './types'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const problem = (status: number, detail: string) => json({ title: 'Problem', detail }, status)

interface Call { key: string; body: any } // eslint-disable-line @typescript-eslint/no-explicit-any

/** An API with head office's outlets and staff kept as state, so each change shows on the next read. */
function stubApi({ changedElsewhere = false, holdReload = false } = {}) {
  // holdReload: the staff list is read once, then every read waits until release() is called.
  let release: () => void = () => {}
  const held = new Promise<void>((resolve) => { release = resolve })
  let staffReads = 0
  // changedElsewhere: another admin saves Aisyah first, so the first edit of her is refused (409) and a reload shows theirs.
  let elsewhereSaved = false
  const calls: Call[] = []
  const outlets: AdminOutlet[] = [
    { id: 1, code: 'O1', name: 'Counterline Bukit Bintang', address: '1 Test Street', active: true },
    { id: 2, code: 'O2', name: 'Counterline Cheras', address: null, active: true },
  ]
  const staff: AdminStaff[] = [
    { id: 10, fullName: 'Aisyah Rahman', role: 'STAFF', outletId: 1, active: true, email: null, version: 3 },
    { id: 11, fullName: 'Daniel Lim', role: 'MANAGER', outletId: 1, active: true, email: 'daniel@counterline.test', version: 4 },
    { id: 12, fullName: 'Former Cashier', role: 'STAFF', outletId: 2, active: false, email: null, version: 5 },
    { id: 13, fullName: 'Noor Aziz', role: 'MANAGER', outletId: 2, active: true, email: null, version: 6 },
  ]
  let nextId = 100
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://pos.test')
    const method = init.method ?? 'GET'
    const key = `${method} ${url.pathname}`
    const body = init.body ? JSON.parse(String(init.body)) : null
    calls.push({ key, body })
    const head = new Headers(init.headers).get('Authorization') === 'Bearer admin-jwt'
    const admin = '/api/v1/dashboard/admin'
    if (key.startsWith(`${method} ${admin}`) && !head) return problem(403, 'Forbidden')

    switch (key) {
      case 'POST /api/v1/dashboard/auth/login':
        return body.email === 'meiling@counterline.test'
          ? json({ token: 'manager-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Mei Ling Tan', role: 'MANAGER', outlet: outlets[1] })
          : json({ token: 'admin-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Head Office', role: 'ADMIN', outlet: null })
      case 'GET /api/v1/dashboard/overview':
      case 'GET /api/v1/dashboard/live':
        return problem(404, 'not stubbed')
      case `GET ${admin}/outlets`:
        return json(outlets)
      case `POST ${admin}/outlets`: {
        if (outlets.some((outlet) => outlet.code.toUpperCase() === String(body.code).toUpperCase())) {
          return problem(409, `An outlet with code ${String(body.code).toUpperCase()} already exists`)
        }
        const created = { id: nextId++, code: String(body.code).toUpperCase(), name: body.name, address: body.address || null, active: true }
        outlets.push(created)
        return json(created, 201)
      }
      case `GET ${admin}/staff`: {
        staffReads++
        if (holdReload && staffReads > 1) await held
        const only = url.searchParams.get('outletId')
        return json(only === null ? staff : staff.filter((member) => member.outletId === Number(only)))
      }
      case `POST ${admin}/staff`: {
        if (body.pin === '111111') return problem(409, 'That PIN is already in use. Choose another.')
        const created: AdminStaff = { id: nextId++, fullName: body.fullName, role: body.role, outletId: body.outletId, active: true, email: body.email ?? null, version: 0 }
        staff.push(created)
        return json(created, 201)
      }
      default: {
        const outlet = /^(PUT|POST) \/api\/v1\/dashboard\/admin\/outlets\/(\d+)(?:\/(deactivate|reactivate))?$/.exec(key)
        if (outlet) {
          const target = outlets.find((candidate) => candidate.id === Number(outlet[2]))!
          if (outlet[3] === 'deactivate') {
            if (target.id === 1) return problem(409, 'Outlet O1 has an open register. Close it before deactivating the outlet.')
            target.active = false
          } else if (outlet[3] === 'reactivate') target.active = true
          else Object.assign(target, { name: body.name, address: body.address || null })
          return json(target)
        }
        const login = /^DELETE \/api\/v1\/dashboard\/admin\/staff\/(\d+)\/dashboard-login$/.exec(key)
        if (login) {
          const target = staff.find((candidate) => candidate.id === Number(login[1]))!
          target.email = null
          target.version++
          return json(target)
        }
        const member = /^(PUT|POST) \/api\/v1\/dashboard\/admin\/staff\/(\d+)(?:\/(deactivate|reactivate|pin))?$/.exec(key)
        if (member) {
          const target = staff.find((candidate) => candidate.id === Number(member[2]))!
          if (member[3] === 'deactivate') { target.active = false; target.version++ }
          else if (member[3] === 'reactivate') { target.active = true; target.version++ }
          else if (member[3] === 'pin') {
            if (body.pin === '111111') return problem(409, 'That PIN is already in use. Choose another.')
            target.version++
          } else {
            if (changedElsewhere && target.id === 10 && !elsewhereSaved) {
              elsewhereSaved = true
              Object.assign(target, { fullName: 'Aisyah Binti Rahman', version: 9 })
              return problem(409, 'Changed elsewhere. Reload.')
            }
            if (body.version !== target.version) return problem(409, 'Changed elsewhere. Reload.')
            const adding = target.email === null && (body.email || body.password)
            if (adding && !(body.email && body.password)) return problem(400, 'Give both an e-mail and a password to add a dashboard login')
            if (body.password && body.password.length < 12) return problem(400, 'The password must be at least 12 characters')
            if (body.email === 'taken@counterline.test') return problem(409, 'That e-mail address is already in use')
            Object.assign(target, { fullName: body.fullName, role: body.role, outletId: body.outletId })
            if (body.role === 'STAFF') target.email = null
            else if (body.email) target.email = body.email
            target.version++
          }
          return json(target)
        }
        return json({ title: 'Not found' }, 404)
      }
    }
  }))
  return Object.assign(calls, { release: () => release() })
}

const sent = (calls: Call[], key: string) => calls.filter((call) => call.key === key)
const admin = '/api/v1/dashboard/admin'

async function logIn(user: UserEvent, email: string) {
  render(<DashboardApp />)
  await user.type(screen.getByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), 'correct horse')
  await user.click(screen.getByRole('button', { name: 'Log in' }))
}

const nav = () => within(screen.getByRole('navigation', { name: 'Dashboard' }))

async function open(user: UserEvent, page: 'Outlets' | 'Staff') {
  await logIn(user, 'admin@counterline.test')
  await user.click(await nav().findByRole('button', { name: page }))
  await screen.findByRole('heading', { name: page })
}

beforeEach(() => sessionStorage.clear())
afterEach(() => {
  setAuthToken(null)
  vi.restoreAllMocks()
})

describe('outlets', () => {
  it('lists every outlet, adds one with a code, and shows a conflict without losing the form', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Outlets')

    const list = await screen.findByRole('region', { name: 'Outlet list' })
    expect(within(list).getByText('O1')).toBeInTheDocument()
    expect(within(list).getByLabelText('Name of outlet O2')).toHaveValue('Counterline Cheras')
    // The code is never editable once the outlet exists.
    expect(within(list).queryByLabelText(/Code of outlet/)).not.toBeInTheDocument()

    const form = screen.getByRole('form', { name: 'Add an outlet' })
    await user.type(within(form).getByLabelText('Code'), 'o2')
    await user.type(within(form).getByLabelText('Name'), 'Counterline Ampang')
    await user.click(within(form).getByRole('button', { name: 'Add outlet' }))
    expect(await screen.findByText('An outlet with code O2 already exists')).toBeInTheDocument()
    expect(within(form).getByLabelText('Code')).toHaveValue('o2')

    await user.clear(within(form).getByLabelText('Code'))
    await user.type(within(form).getByLabelText('Code'), 'o3')
    await user.type(within(form).getByLabelText('Address'), '3 Test Road')
    await user.click(within(form).getByRole('button', { name: 'Add outlet' }))
    expect(await screen.findByText('Added Counterline Ampang')).toBeInTheDocument()
    expect(await within(await screen.findByRole('region', { name: 'Outlet list' })).findByText('O3')).toBeInTheDocument()
    expect(sent(calls, `POST ${admin}/outlets`)[1].body).toEqual({ code: 'o3', name: 'Counterline Ampang', address: '3 Test Road' })
    expect(within(form).getByLabelText('Code')).toHaveValue('')
  })

  it('saves a changed name and address, never the code', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Outlets')

    expect(await screen.findByRole('button', { name: 'Save outlet O2' })).toBeDisabled()
    const name = screen.getByLabelText('Name of outlet O2')
    await user.clear(name)
    await user.type(name, 'Counterline Cheras Selatan')
    await user.type(screen.getByLabelText('Address of outlet O2'), '9 New Road')
    await user.click(screen.getByRole('button', { name: 'Save outlet O2' }))

    expect(await screen.findByText('Saved Counterline Cheras Selatan')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/outlets/2`)[0].body).toEqual({ name: 'Counterline Cheras Selatan', address: '9 New Road' })
  })

  it('deactivates only after confirming, reports an open register, and reactivates', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Outlets')

    await user.click(await screen.findByRole('button', { name: 'Deactivate outlet O2' }))
    await answerConfirm(user, 'Deactivate Counterline Cheras?', 'Cancel')
    expect(sent(calls, `POST ${admin}/outlets/2/deactivate`)).toHaveLength(0)

    // The open register makes the server refuse: the clear 409 text is shown and the outlet stays open.
    await user.click(screen.getByRole('button', { name: 'Deactivate outlet O1' }))
    await answerConfirm(user, 'Deactivate Counterline Bukit Bintang?', 'Deactivate outlet')
    expect(await screen.findByText('Outlet O1 has an open register. Close it before deactivating the outlet.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Deactivate outlet O1' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Deactivate outlet O2' }))
    await answerConfirm(user, 'Deactivate Counterline Cheras?', 'Deactivate outlet')
    expect(await screen.findByText('Deactivated Counterline Cheras')).toBeInTheDocument()
    await user.click(await screen.findByRole('button', { name: 'Reactivate outlet O2' }))
    expect(await screen.findByText('Reactivated Counterline Cheras')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Deactivate outlet O2' })).toBeInTheDocument()
  })
})

describe('staff', () => {
  it('lists staff by outlet, deactivated ones included', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    expect(await screen.findByLabelText('Name of Aisyah Rahman')).toBeInTheDocument()
    expect(screen.getByLabelText('Name of Former Cashier')).toBeInTheDocument()
    expect(screen.getByText('Dashboard login: daniel@counterline.test')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reactivate Former Cashier' })).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Outlet'), 'O2 · Counterline Cheras')
    expect(await screen.findByRole('heading', { name: 'Staff at Counterline Cheras' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Name of Aisyah Rahman')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Name of Former Cashier')).toBeInTheDocument()
    expect(sent(calls, `GET ${admin}/staff`).length).toBeGreaterThanOrEqual(2)
  })

  it('adds a cashier without a login and a manager with one, and a taken PIN is refused', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    const form = await screen.findByRole('form', { name: 'Add a staff member' })
    // A cashier has no dashboard login fields.
    expect(within(form).queryByLabelText(/Dashboard/)).not.toBeInTheDocument()
    await user.type(within(form).getByLabelText('Name'), 'New Cashier')
    await user.type(within(form).getByLabelText('PIN (6 digits)'), '111111')
    await user.click(within(form).getByRole('button', { name: 'Add staff member' }))
    expect(await screen.findByText('That PIN is already in use. Choose another.')).toBeInTheDocument()
    expect(within(form).getByLabelText('Name')).toHaveValue('New Cashier')

    await user.clear(within(form).getByLabelText('PIN (6 digits)'))
    await user.type(within(form).getByLabelText('PIN (6 digits)'), '246810')
    await user.click(within(form).getByRole('button', { name: 'Add staff member' }))
    expect(await screen.findByText('Added New Cashier')).toBeInTheDocument()
    expect(sent(calls, `POST ${admin}/staff`)[1].body).toEqual({ fullName: 'New Cashier', role: 'STAFF', outletId: 1, pin: '246810' })
    // The PIN is cleared from the form and never shown in the list.
    expect(within(form).getByLabelText('PIN (6 digits)')).toHaveValue('')
    expect(screen.queryByText('246810')).not.toBeInTheDocument()

    await user.selectOptions(within(form).getByLabelText('Role'), 'MANAGER')
    await user.type(within(form).getByLabelText('Name'), 'New Manager')
    await user.type(within(form).getByLabelText('PIN (6 digits)'), '135790')
    await user.type(within(form).getByLabelText('Dashboard e-mail (optional)'), 'new.manager@counterline.test')
    await user.type(within(form).getByLabelText(/^Dashboard password/), 'a-long-test-password')
    await user.click(within(form).getByRole('button', { name: 'Add staff member' }))
    expect(await screen.findByText('Added New Manager')).toBeInTheDocument()
    expect(sent(calls, `POST ${admin}/staff`)[2].body).toEqual({
      fullName: 'New Manager', role: 'MANAGER', outletId: 1, pin: '135790', email: 'new.manager@counterline.test', password: 'a-long-test-password',
    })
  })

  it('edits name, role and outlet, asking first before a manager loses the dashboard login', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    expect(await screen.findByRole('button', { name: 'Save Aisyah Rahman' })).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Outlet of Aisyah Rahman'), 'O2 · Counterline Cheras')
    await user.click(screen.getByRole('button', { name: 'Save Aisyah Rahman' }))
    expect(await screen.findByText('Saved Aisyah Rahman')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/staff/10`)[0].body).toEqual({ fullName: 'Aisyah Rahman', role: 'STAFF', outletId: 2, version: 3 })
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    // Demoting a manager removes their login: declined sends nothing, accepted saves.
    await user.selectOptions(screen.getByLabelText('Role of Daniel Lim'), 'STAFF')
    await user.click(screen.getByRole('button', { name: 'Save Daniel Lim' }))
    await answerConfirm(user, 'Make Daniel Lim a cashier?', 'Cancel')
    expect(sent(calls, `PUT ${admin}/staff/11`)).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Save Daniel Lim' }))
    await answerConfirm(user, 'Make Daniel Lim a cashier?', 'Make cashier')
    expect(await screen.findByText('Saved Daniel Lim')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/staff/11`)[0].body).toEqual({ fullName: 'Daniel Lim', role: 'STAFF', outletId: 1, version: 4 })
    expect(screen.queryByText(/Dashboard login: daniel/)).not.toBeInTheDocument()
  })

  it('a person changed by someone else since the list loaded is refused, and the row is rebuilt from their version', async () => {
    const calls = stubApi({ changedElsewhere: true })
    const user = userEvent.setup()
    await open(user, 'Staff')

    await screen.findByRole('button', { name: 'Save Aisyah Rahman' })
    await user.selectOptions(screen.getByLabelText('Outlet of Aisyah Rahman'), 'O2 · Counterline Cheras')
    await user.click(screen.getByRole('button', { name: 'Save Aisyah Rahman' }))

    // The refusal is shown, nothing is claimed as saved, and the row now shows what the other admin saved.
    expect(await screen.findByText('Changed elsewhere. Reload.')).toBeInTheDocument()
    expect(screen.queryByText('Saved Aisyah Rahman')).not.toBeInTheDocument()
    expect(await screen.findByLabelText('Name of Aisyah Binti Rahman')).toHaveValue('Aisyah Binti Rahman')
    expect(sent(calls, `PUT ${admin}/staff/10`)).toHaveLength(1)
    expect(sent(calls, `PUT ${admin}/staff/10`)[0].body).toMatchObject({ version: 3 })

    // Saving again from the rebuilt row carries the newer version and goes through.
    await user.selectOptions(screen.getByLabelText('Outlet of Aisyah Binti Rahman'), 'O2 · Counterline Cheras')
    await user.click(screen.getByRole('button', { name: 'Save Aisyah Binti Rahman' }))
    expect(await screen.findByText('Saved Aisyah Binti Rahman')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/staff/10`)[1].body).toMatchObject({ outletId: 2, version: 9 })
  })

  it('while the list reloads after a save there is no Save to press, and the next one carries the version the server returned', async () => {
    const calls = stubApi({ holdReload: true })
    const user = userEvent.setup()
    await open(user, 'Staff')

    await screen.findByRole('button', { name: 'Save Aisyah Rahman' })
    await user.selectOptions(screen.getByLabelText('Outlet of Aisyah Rahman'), 'O2 · Counterline Cheras')
    await user.click(screen.getByRole('button', { name: 'Save Aisyah Rahman' }))
    await vi.waitFor(() => expect(sent(calls, `PUT ${admin}/staff/10`)).toHaveLength(1))

    // The reload is held back: the list is replaced by its loading state, so the old version cannot be sent again.
    expect(await screen.findByText('Saved Aisyah Rahman')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Save / })).not.toBeInTheDocument()
    calls.release()

    const name = await screen.findByLabelText('Name of Aisyah Rahman')
    await user.type(name, ' B')
    await user.click(screen.getByRole('button', { name: 'Save Aisyah Rahman' }))
    expect(await screen.findByText('Saved Aisyah Rahman B')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/staff/10`)[0].body).toMatchObject({ outletId: 2, version: 3 })
    expect(sent(calls, `PUT ${admin}/staff/10`)[1].body).toMatchObject({ fullName: 'Aisyah Rahman B', version: 4 })
  })

  it('deactivates only after confirming, and reactivates', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    await user.click(await screen.findByRole('button', { name: 'Deactivate Aisyah Rahman' }))
    await answerConfirm(user, 'Deactivate Aisyah Rahman?', 'Cancel')
    expect(sent(calls, `POST ${admin}/staff/10/deactivate`)).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Deactivate Aisyah Rahman' }))
    await answerConfirm(user, 'Deactivate Aisyah Rahman?', 'Deactivate')
    expect(await screen.findByText('Deactivated Aisyah Rahman')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Reactivate Aisyah Rahman' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Reactivate Former Cashier' }))
    expect(await screen.findByText('Reactivated Former Cashier')).toBeInTheDocument()
    expect(sent(calls, `POST ${admin}/staff/12/reactivate`)).toHaveLength(1)
  })

  it('resets a PIN with six digits, sends it once, and never shows it', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    await user.click(await screen.findByRole('button', { name: 'Reset PIN of Aisyah Rahman' }))
    const set = screen.getByRole('button', { name: 'Set PIN of Aisyah Rahman' })
    expect(set).toBeDisabled()
    const pin = screen.getByLabelText('New PIN for Aisyah Rahman')
    expect(pin).toHaveAttribute('type', 'password')
    await user.type(pin, '111111')
    await user.click(set)
    expect(await screen.findByText('That PIN is already in use. Choose another.')).toBeInTheDocument()

    await user.clear(pin)
    await user.type(pin, '864209')
    await user.click(set)
    expect(await screen.findByText('Reset the PIN of Aisyah Rahman')).toBeInTheDocument()
    expect(sent(calls, `POST ${admin}/staff/10/pin`).map((call) => call.body)).toEqual([{ pin: '111111' }, { pin: '864209' }])
    expect(screen.queryByLabelText('New PIN for Aisyah Rahman')).not.toBeInTheDocument()
    expect(screen.queryByText('864209')).not.toBeInTheDocument()
  })
})

describe('dashboard logins of existing managers', () => {
  const openLogin = async (user: UserEvent, name: string) => {
    await user.click(await screen.findByRole('button', { name: `Dashboard login of ${name}` }))
    return screen.getByRole('form', { name: `Dashboard login of ${name}` })
  }

  it('adds a login to a manager who has none, only with an e-mail and a password together', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    // Cashiers are offered no dashboard login.
    await screen.findByLabelText('Name of Aisyah Rahman')
    expect(screen.queryByRole('button', { name: 'Dashboard login of Aisyah Rahman' })).not.toBeInTheDocument()

    const form = await openLogin(user, 'Noor Aziz')
    const save = within(form).getByRole('button', { name: 'Save dashboard login of Noor Aziz' })
    expect(within(form).queryByRole('button', { name: /Remove dashboard login/ })).not.toBeInTheDocument()
    expect(within(form).getByLabelText('Dashboard password of Noor Aziz')).toHaveAttribute('type', 'password')
    await user.type(within(form).getByLabelText('Dashboard e-mail of Noor Aziz'), 'noor@counterline.test')
    expect(save).toBeDisabled()
    await user.type(within(form).getByLabelText('Dashboard password of Noor Aziz'), 'short')
    await user.click(save)
    // Refused by the server: the typed values stay for correction.
    expect(await screen.findByText('The password must be at least 12 characters')).toBeInTheDocument()
    expect(within(form).getByLabelText('Dashboard e-mail of Noor Aziz')).toHaveValue('noor@counterline.test')
    expect(within(form).getByLabelText('Dashboard password of Noor Aziz')).toHaveValue('short')

    await user.clear(within(form).getByLabelText('Dashboard password of Noor Aziz'))
    await user.type(within(form).getByLabelText('Dashboard password of Noor Aziz'), 'a-long-test-password')
    await user.click(save)
    expect(await screen.findByText('Added a dashboard login for Noor Aziz')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/staff/13`)[1].body)
      .toEqual({ fullName: 'Noor Aziz', role: 'MANAGER', outletId: 2, version: 6, email: 'noor@counterline.test', password: 'a-long-test-password' })
    expect(await screen.findByText('Dashboard login: noor@counterline.test')).toBeInTheDocument()
  })

  it('changes only the e-mail, or only the password, and never sends a blank password', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    const form = await openLogin(user, 'Daniel Lim')
    const email = within(form).getByLabelText('Dashboard e-mail of Daniel Lim')
    const password = within(form).getByLabelText('Dashboard password of Daniel Lim')
    const save = within(form).getByRole('button', { name: 'Save dashboard login of Daniel Lim' })
    expect(email).toHaveValue('daniel@counterline.test')
    expect(password).toHaveValue('')
    expect(within(form).getByText('Leave blank to keep the current password')).toBeInTheDocument()
    // Nothing changed, nothing to save.
    expect(save).toBeDisabled()

    await user.clear(email)
    await user.type(email, 'daniel.lim@counterline.test')
    await user.click(save)
    expect(await screen.findByText('Updated the dashboard login of Daniel Lim')).toBeInTheDocument()
    expect(sent(calls, `PUT ${admin}/staff/11`)[0].body).toEqual({ fullName: 'Daniel Lim', role: 'MANAGER', outletId: 1, version: 4, email: 'daniel.lim@counterline.test' })

    const again = await openLogin(user, 'Daniel Lim')
    await user.type(within(again).getByLabelText('Dashboard password of Daniel Lim'), 'another-long-password')
    await user.click(within(again).getByRole('button', { name: 'Save dashboard login of Daniel Lim' }))
    expect(await screen.findAllByText('Updated the dashboard login of Daniel Lim')).not.toHaveLength(0)
    expect(sent(calls, `PUT ${admin}/staff/11`)[1].body).toEqual({ fullName: 'Daniel Lim', role: 'MANAGER', outletId: 1, version: 5, password: 'another-long-password' })
    // The password field is emptied after the save.
    expect(within(screen.getByRole('form', { name: 'Dashboard login of Daniel Lim' })).getByLabelText('Dashboard password of Daniel Lim')).toHaveValue('')
    expect(screen.queryByText('another-long-password')).not.toBeInTheDocument()
  })

  it('keeps a failed e-mail change in the form', async () => {
    stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')
    const form = await openLogin(user, 'Daniel Lim')
    const email = within(form).getByLabelText('Dashboard e-mail of Daniel Lim')
    await user.clear(email)
    await user.type(email, 'taken@counterline.test')
    await user.click(within(form).getByRole('button', { name: 'Save dashboard login of Daniel Lim' }))
    expect(await screen.findByText('That e-mail address is already in use')).toBeInTheDocument()
    expect(email).toHaveValue('taken@counterline.test')
  })

  it('removes a login only after confirming', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await open(user, 'Staff')

    const form = await openLogin(user, 'Daniel Lim')
    await user.click(within(form).getByRole('button', { name: 'Remove dashboard login of Daniel Lim' }))
    await answerConfirm(user, 'Remove the dashboard login of Daniel Lim?', 'Cancel')
    expect(sent(calls, `DELETE ${admin}/staff/11/dashboard-login`)).toHaveLength(0)

    await user.click(within(form).getByRole('button', { name: 'Remove dashboard login of Daniel Lim' }))
    await answerConfirm(user, 'Remove the dashboard login of Daniel Lim?', 'Remove login')
    expect(await screen.findByText('Removed the dashboard login of Daniel Lim')).toBeInTheDocument()
    expect(sent(calls, `DELETE ${admin}/staff/11/dashboard-login`)).toHaveLength(1)
    expect(screen.queryByText(/Dashboard login: daniel/)).not.toBeInTheDocument()
    // Back to having none: adding one needs both fields again.
    const empty = await openLogin(user, 'Daniel Lim')
    expect(within(empty).queryByRole('button', { name: /Remove dashboard login/ })).not.toBeInTheDocument()
  })
})

describe('authorization', () => {
  it('an outlet manager is not offered the outlet and staff pages', async () => {
    stubApi()
    const user = userEvent.setup()
    await logIn(user, 'meiling@counterline.test')
    await nav().findByRole('button', { name: 'Customers' })
    expect(nav().queryByRole('button', { name: 'Outlets' })).not.toBeInTheDocument()
    expect(nav().queryByRole('button', { name: 'Staff' })).not.toBeInTheDocument()
  })

  it('shows the server refusing a caller that is not head office', async () => {
    stubApi()
    const user = userEvent.setup()
    await open(user, 'Outlets')
    // The same page with a token the server rejects: the refusal is shown, not a blank page.
    setAuthToken('manager-jwt')
    await user.click(await screen.findByRole('button', { name: 'Deactivate outlet O2' }))
    await answerConfirm(user, 'Deactivate Counterline Cheras?', 'Deactivate outlet')
    expect(await screen.findByText('Forbidden')).toBeInTheDocument()
  })
})
