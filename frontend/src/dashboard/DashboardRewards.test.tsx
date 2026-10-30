import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthToken } from '../api/http'
import { DashboardApp } from './DashboardApp'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const member = {
  id: 5, version: 0, referenceId: null, firstName: 'Nur', lastName: 'Aisyah', fullName: 'Nur Aisyah', companyName: null,
  dateOfBirth: null, gender: null, email: null, phone: '+60112345678', marketingEmail: false, marketingSms: false,
  marketingCall: false, streetAddress: null, aptSuite: null, city: null, postalCode: null, country: null, notes: null,
  createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z',
}

/** `overrides`: answers to particular calls (by "METHOD /path"), in place of the in-memory ones. */
function stubApi(overrides: Record<string, (body: Record<string, unknown> | null) => Response> = {}) {
  const calls: { key: string; body: unknown }[] = []
  let balance = 3
  const entries: unknown[] = []
  const owed: unknown[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://pos.test')
    const key = `${init.method ?? 'GET'} ${url.pathname}`
    const body = init.body ? JSON.parse(String(init.body)) : null
    calls.push({ key, body })
    const override = overrides[key]
    if (override) return override(body)
    switch (key) {
      case 'GET /api/v1/dashboard/live':
        return json({ date: '2026-10-13', orderCount: 0, netSales: 0, outlets: [], generatedAt: '2026-10-13T02:00:00Z' })
      case 'GET /api/v1/dashboard/vouchers':
        return json([])
      case 'POST /api/v1/dashboard/vouchers':
        return json({
          batch: { id: 1, name: body.name, value: body.value, expiresOn: body.expiresOn, issued: 2, used: 0, cancelled: 0,
            createdBy: 'Head Office', createdAt: '2026-10-13T02:00:00Z' },
          codes: ['K7MPQ2XW9A', 'HT4R8ZPQ2M'],
        }, 201)
      case 'GET /api/v1/customers':
        return json({ items: [member], page: 0, size: 20, totalItems: 1, totalPages: 1 })
      case 'GET /api/v1/customers/5':
        return json(member)
      case 'GET /api/v1/customers/5/analytics':
        return json({ orderCount: 0, orderTotal: 0, orderAverage: 0, lastOrderAt: null, mostOrderedItems: [] })
      case 'GET /api/v1/dashboard/customers/5/points':
        return json({ balance, entries })
      case 'POST /api/v1/dashboard/customers/5/points':
        balance += body.points
        entries.unshift({ type: 'ADJUST', points: body.points, at: '2026-10-13T02:00:00Z', orderNumber: null,
          reason: body.reason, adjustedBy: 'Head Office' })
        return json({ balance, entries })
      case 'GET /api/v1/dashboard/customers/5/owed-items':
        return json(owed)
      case 'POST /api/v1/dashboard/customers/5/owed-items':
        owed.push({ id: 1, promotion: 'Birthday Reward', remaining: body.quantity, reason: body.reason, givenBy: 'Head Office',
          givenAt: '2026-10-13T02:00:00Z' })
        return json(owed[0], 201)
      case 'GET /api/v1/dashboard/promotions':
        return json([{ id: 5, name: 'Birthday Reward', items: ['B3 F1 BDG PC Birthday'] }])
      default:
        return json({ title: 'Not found' }, 404)
    }
  }))
  return calls
}

const nav = () => within(screen.getByRole('navigation', { name: 'Dashboard' }))

async function openMember(user: ReturnType<typeof userEvent.setup>) {
  render(<DashboardApp />)
  await user.click(nav().getByRole('button', { name: 'Customers' }))
  await user.click(await screen.findByRole('button', { name: /Nur Aisyah/ }))
  await screen.findByTestId('points-balance')
}

beforeEach(() => {
  sessionStorage.clear()
  sessionStorage.setItem('counterline.dashboard', JSON.stringify({
    token: 'admin-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Head Office', role: 'ADMIN', outlet: null,
  }))
})
afterEach(() => setAuthToken(null))

describe('dashboard: vouchers and member rewards', () => {
  it('head office issues a batch of vouchers and gets the codes to print', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    render(<DashboardApp />)
    await user.click(nav().getByRole('button', { name: 'Vouchers' }))

    const form = await screen.findByRole('form', { name: 'Issue vouchers' })
    await user.type(within(form).getByLabelText('Valid until'), '2026-12-31')
    await user.clear(within(form).getByLabelText('How many'))
    await user.type(within(form).getByLabelText('How many'), '2')
    await user.click(within(form).getByRole('button', { name: 'Issue vouchers' }))

    const sheet = await screen.findByRole('region', { name: 'Voucher codes to print' })
    expect(sheet).toHaveTextContent('K7MPQ2XW9A')
    expect(sheet).toHaveTextContent('HT4R8ZPQ2M')
    expect(sheet).toHaveTextContent('RM10.00 off')
    expect(calls.find((call) => call.key === 'POST /api/v1/dashboard/vouchers')!.body)
      .toEqual({ name: 'RM10 Voucher', value: 10, expiresOn: '2026-12-31', quantity: 2 })
  })

  it('adjusts a member’s points and owes a free item the staff forgot', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    render(<DashboardApp />)
    await user.click(nav().getByRole('button', { name: 'Customers' }))
    await user.click(await screen.findByRole('button', { name: /Nur Aisyah/ }))

    expect(await screen.findByTestId('points-balance')).toHaveTextContent('3 points')
    const adjust = screen.getByRole('form', { name: 'Adjust points' })
    await user.type(within(adjust).getByLabelText('Points to add or take away'), '5')
    await user.type(within(adjust).getByLabelText('Reason for the adjustment'), 'Points missed on R12')
    await user.click(within(adjust).getByRole('button', { name: 'Adjust' }))
    expect(await screen.findByText(/Points missed on R12 \(Head Office\)/)).toBeInTheDocument()
    expect(screen.getByTestId('points-balance')).toHaveTextContent('8 points')

    const owe = screen.getByRole('form', { name: 'Owe a free item' })
    await user.selectOptions(within(owe).getByLabelText('Promotion'), '5')
    await user.type(within(owe).getByLabelText('Reason for owing'), 'Forgot the birthday can on R40')
    await user.click(within(owe).getByRole('button', { name: 'Owe item' }))
    expect(await screen.findByText('1 × Birthday Reward')).toBeInTheDocument()
    expect(calls.find((call) => call.key === 'POST /api/v1/dashboard/customers/5/owed-items')!.body)
      .toEqual({ promotionId: 5, quantity: 1, reason: 'Forgot the birthday can on R40' })
  })

  it('says what is wrong with the points typed, and sends nothing until they are right', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await openMember(user)
    const adjust = screen.getByRole('form', { name: 'Adjust points' })
    const points = within(adjust).getByLabelText('Points to add or take away')
    const button = within(adjust).getByRole('button', { name: 'Adjust' })
    await user.type(within(adjust).getByLabelText('Reason for the adjustment'), 'Points missed on R12')

    for (const [typed, problem] of [
      ['2.5', 'Points must be a whole number'], ['1.9', 'Points must be a whole number'], ['-2.5', 'Points must be a whole number'],
      ['0.5', 'Points must be a whole number'], ['0', "Points can't be 0"], ['-0', "Points can't be 0"],
      ['10001', 'Points must be from -10,000 to 10,000'], ['-10001', 'Points must be from -10,000 to 10,000'],
    ]) {
      await user.clear(points)
      await user.type(points, typed)
      expect(within(adjust).getByRole('alert'), typed).toHaveTextContent(problem)
      expect(button, typed).toBeDisabled()
    }
    // Both ends of the range, and the nearest whole numbers to nothing, are fine.
    for (const typed of ['10000', '-10000', '1', '-1']) {
      await user.clear(points)
      await user.type(points, typed)
      expect(within(adjust).queryByRole('alert'), typed).not.toBeInTheDocument()
      expect(button, typed).toBeEnabled()
    }
    expect(calls.some((call) => call.key === 'POST /api/v1/dashboard/customers/5/points')).toBe(false)
  })

  it('asks for a reason once the field has been left empty', async () => {
    stubApi()
    const user = userEvent.setup()
    await openMember(user)
    const adjust = screen.getByRole('form', { name: 'Adjust points' })
    await user.type(within(adjust).getByLabelText('Points to add or take away'), '3')
    expect(within(adjust).queryByRole('alert')).not.toBeInTheDocument()

    await user.click(within(adjust).getByLabelText('Reason for the adjustment'))
    await user.tab()
    expect(within(adjust).getByRole('alert')).toHaveTextContent('Give a reason for the adjustment')
    expect(within(adjust).getByRole('button', { name: 'Adjust' })).toBeDisabled()

    await user.type(within(adjust).getByLabelText('Reason for the adjustment'), 'Typo')
    expect(within(adjust).queryByRole('alert')).not.toBeInTheDocument()
    expect(within(adjust).getByRole('button', { name: 'Adjust' })).toBeEnabled()
  })

  it('shows what the server refused an adjustment for, and clears it as soon as a field is edited', async () => {
    stubApi({
      'POST /api/v1/dashboard/customers/5/points': () => json({ title: 'Request cannot be completed', detail: 'Nur Aisyah has only 3 points' }, 422),
    })
    const user = userEvent.setup()
    await openMember(user)
    const adjust = screen.getByRole('form', { name: 'Adjust points' })
    const points = within(adjust).getByLabelText('Points to add or take away')
    const reason = within(adjust).getByLabelText('Reason for the adjustment')

    await user.type(points, '-9')
    await user.type(reason, 'Typo')
    await user.click(within(adjust).getByRole('button', { name: 'Adjust' }))
    expect(await within(adjust).findByRole('alert')).toHaveTextContent('Nur Aisyah has only 3 points')

    // Editing either field takes the old answer away at once.
    await user.type(points, '1')
    expect(within(adjust).queryByRole('alert')).not.toBeInTheDocument()
    await user.click(within(adjust).getByRole('button', { name: 'Adjust' }))
    expect(await within(adjust).findByRole('alert')).toHaveTextContent('Nur Aisyah has only 3 points')
    await user.type(reason, '!')
    expect(within(adjust).queryByRole('alert')).not.toBeInTheDocument()
  })

  it('never shows a raw failure to the user', async () => {
    stubApi({
      'POST /api/v1/dashboard/customers/5/points': () => { throw new TypeError('NetworkError when attempting to fetch resource.') },
    })
    const user = userEvent.setup()
    await openMember(user)
    const adjust = screen.getByRole('form', { name: 'Adjust points' })
    await user.type(within(adjust).getByLabelText('Points to add or take away'), '4')
    await user.type(within(adjust).getByLabelText('Reason for the adjustment'), 'Typo')
    await user.click(within(adjust).getByRole('button', { name: 'Adjust' }))

    expect(await within(adjust).findByRole('alert')).toHaveTextContent(/^Offline$/)
  })

  it('checks how many are owed, what from and why, and clears a refusal as soon as a field is edited', async () => {
    const calls = stubApi({
      'POST /api/v1/dashboard/customers/5/owed-items': () => json({ title: 'Request cannot be completed', detail: 'Birthday Reward is not running' }, 422),
    })
    const user = userEvent.setup()
    await openMember(user)
    const owe = screen.getByRole('form', { name: 'Owe a free item' })
    const how = within(owe).getByLabelText('How many')
    const button = within(owe).getByRole('button', { name: 'Owe item' })
    await user.selectOptions(within(owe).getByLabelText('Promotion'), '5')
    await user.type(within(owe).getByLabelText('Reason for owing'), 'Forgot the birthday can on R40')

    for (const typed of ['0', '11', '2.5', '-1']) {
      await user.clear(how)
      await user.type(how, typed)
      expect(within(owe).getByRole('alert'), typed).toHaveTextContent('How many must be a whole number from 1 to 10')
      expect(button, typed).toBeDisabled()
    }
    await user.clear(how)
    await user.type(how, '10')
    expect(within(owe).queryByRole('alert')).not.toBeInTheDocument()
    expect(calls.some((call) => call.key === 'POST /api/v1/dashboard/customers/5/owed-items')).toBe(false)

    await user.click(button)
    expect(await within(owe).findByRole('alert')).toHaveTextContent('Birthday Reward is not running')
    await user.clear(how)
    await user.type(how, '2')
    expect(within(owe).queryByRole('alert')).not.toBeInTheDocument()

    await user.click(button)
    expect(await within(owe).findByRole('alert')).toHaveTextContent('Birthday Reward is not running')
    await user.type(within(owe).getByLabelText('Reason for owing'), '!')
    expect(within(owe).queryByRole('alert')).not.toBeInTheDocument()

    await user.click(button)
    expect(await within(owe).findByRole('alert')).toBeInTheDocument()
    await user.selectOptions(within(owe).getByLabelText('Promotion'), '5')
    expect(within(owe).queryByRole('alert')).not.toBeInTheDocument()
  })
})
