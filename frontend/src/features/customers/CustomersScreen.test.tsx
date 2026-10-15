import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { CustomersScreen } from './CustomersScreen'
import type { Customer, CustomerAnalytics } from './types'

function makeCustomer(id: number, firstName: string, overrides: Partial<Customer> = {}): Customer {
  return {
    id, version: 0, referenceId: null, firstName, lastName: null, fullName: firstName, companyName: null,
    dateOfBirth: null, gender: null, email: null, phone: null, marketingEmail: false, marketingSms: false,
    marketingCall: false, streetAddress: null, aptSuite: null, city: null, postalCode: null, country: null,
    notes: null, createdAt: '2026-10-12T10:00:00Z', updatedAt: '2026-10-12T10:00:00Z', ...overrides,
  }
}

const analytics: CustomerAnalytics = {
  orderCount: 12, orderTotal: 968.9, orderAverage: 80.74, lastOrderAt: '2026-08-21T12:27:00Z',
  mostOrderedItems: [{ itemName: 'Pouch Butter Caramel', quantity: 10 }, { itemName: 'Pouch Cheese', quantity: 6 }],
}

function json(body: unknown, status = 200) {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  })
}

interface Call {
  method: string
  url: URL
  body: Record<string, unknown> | undefined
}

/** In-memory customers API; `onPut` lets a test simulate a conflicting edit. */
function fakeApi(customers: Customer[], options: { onPut?: () => Response } = {}) {
  const calls: Call[] = []
  const byId = new Map(customers.map((c) => [c.id, c]))
  let nextId = 100

  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = new URL(String(input), 'http://pos.test')
      const method = init.method ?? 'GET'
      const body = init.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, url, body })

      if (url.pathname === '/api/v1/customers' && method === 'GET') {
        const q = (url.searchParams.get('q') ?? '').toLowerCase()
        const field = url.searchParams.get('field')
        const items = [...byId.values()].filter((c) =>
          !q || (field === 'PHONE' ? (c.phone ?? '').includes(q.replace(/^0/, '60')) : c.fullName.toLowerCase().includes(q)),
        )
        return json({ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 })
      }
      if (url.pathname === '/api/v1/customers' && method === 'POST') {
        const created = makeCustomer(nextId++, body.firstName, { ...body, fullName: body.firstName })
        byId.set(created.id, created)
        return json(created, 201)
      }
      const match = /^\/api\/v1\/customers\/(\d+)(\/analytics)?$/.exec(url.pathname)
      const customer = match ? byId.get(Number(match[1])) : undefined
      if (!customer) return json({ title: 'Not found', detail: 'Customer not found' }, 404)
      if (match![2]) return json(analytics)
      if (method === 'GET') return json(customer)
      if (method === 'PUT') {
        if (options.onPut) return options.onPut()
        const updated = { ...customer, ...body, version: customer.version + 1 }
        byId.set(customer.id, updated)
        return json(updated)
      }
      if (method === 'DELETE') {
        byId.delete(customer.id)
        return json(undefined, 204)
      }
      return json({}, 405)
    }),
  )
  return calls
}

const list = () => within(screen.getByRole('region', { name: 'Customers' }))
const detail = () => within(screen.getByRole('region', { name: 'Customer detail' }))

describe('CustomersScreen', () => {
  it('searches by the chosen field', async () => {
    const calls = fakeApi([
      makeCustomer(1, 'Nur', { phone: '+60112345678' }),
      makeCustomer(2, 'Daniel', { phone: '+60127788990' }),
    ])
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await list().findByRole('button', { name: /Daniel/ })
    await user.click(screen.getByRole('tab', { name: 'Phone' }))
    await user.type(screen.getByRole('searchbox', { name: 'Search customers' }), '0112345')

    await waitFor(() => expect(list().queryByRole('button', { name: /Daniel/ })).not.toBeInTheDocument())
    expect(list().getByRole('button', { name: /Nur/ })).toBeInTheDocument()
    const lastSearch = calls.filter((call) => call.method === 'GET' && call.url.pathname === '/api/v1/customers').at(-1)!
    expect(Object.fromEntries(lastSearch.url.searchParams)).toMatchObject({ field: 'PHONE', q: '0112345' })
  })

  it('shows customer information and analytics', async () => {
    fakeApi([makeCustomer(1, 'Nur', { phone: '+60112345678', marketingEmail: true, dateOfBirth: '1999-07-11' })])
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await user.click(await list().findByRole('button', { name: /Nur/ }))

    // Most of the number is hidden: the till faces the shop.
    expect(await detail().findByText('+60 11•••5678')).toBeInTheDocument()
    expect(detail().queryByText('+60112345678')).not.toBeInTheDocument()
    expect(detail().getByText('Email')).toBeInTheDocument() // marketing consent
    expect(await detail().findByText('RM80.74')).toBeInTheDocument()
    expect(detail().getByText('10 Pouch Butter Caramel')).toBeInTheDocument()
  })

  it('adds a customer with first name, last name and phone, sending blank fields as null', async () => {
    const calls = fakeApi([])
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Add customer' }))
    const form = screen.getByRole('dialog', { name: 'Add Customer' })
    const save = within(form).getByRole('button', { name: 'Save' })
    await user.type(within(form).getByLabelText(/First name/), 'Nur')
    await user.type(within(form).getByLabelText(/Phone number/), '011-2345 678')
    expect(save).toBeDisabled() // no last name yet
    await user.type(within(form).getByLabelText(/Last name/), 'Aisyah')
    // A local number is Malaysian: the flag and +60 show on the left.
    expect(within(form).getByTestId('phone-country')).toHaveTextContent('+60')
    expect(within(form).getByTestId('phone-country')).toHaveAttribute('title', 'MY')
    await user.click(within(form).getByLabelText('Text (SMS)'))
    await user.click(save)

    expect(await detail().findByRole('heading', { name: 'Nur' })).toBeInTheDocument()
    const post = calls.find((call) => call.method === 'POST')!
    expect(post.body).toMatchObject({ firstName: 'Nur', lastName: 'Aisyah', phone: '+60112345678', marketingSms: true, email: null })
  })

  it('detects the country from a number typed with its code', async () => {
    fakeApi([])
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Add customer' }))
    const form = screen.getByRole('dialog', { name: 'Add Customer' })
    const phone = within(form).getByLabelText(/Phone number/)
    await user.type(phone, '+65')
    // The code moves into the prefix, with Singapore's flag.
    expect(within(form).getByTestId('phone-country')).toHaveAttribute('title', 'SG')
    expect(within(form).getByTestId('phone-country')).toHaveTextContent('+65')
    expect(phone).toHaveValue('')
    await user.type(phone, '9123 4567')
    expect(phone).toHaveValue('9123 4567')
    await user.clear(phone)
    expect(within(form).getByTestId('phone-country')).toHaveAttribute('title', 'MY')
  })

  it('edits with the loaded version and shows a conflict if someone saved first', async () => {
    const calls = fakeApi([makeCustomer(1, 'Nur', { version: 3, phone: '+60112345678' })], {
      onPut: () => json({ title: 'Conflicting change', detail: 'Changed elsewhere. Reload.' }, 409),
    })
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await user.click(await list().findByRole('button', { name: /Nur/ }))
    await user.click(await detail().findByRole('button', { name: 'Edit Customer Details' }))
    const form = screen.getByRole('dialog', { name: 'Edit Customer' })
    await user.type(within(form).getByLabelText(/Last name/), 'Aisyah')
    await user.click(within(form).getByRole('button', { name: 'Save' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent('Changed elsewhere')
    expect(calls.find((call) => call.method === 'PUT')!.body).toMatchObject({ version: 3, lastName: 'Aisyah', phone: '+60112345678' })
  })

  it('keeps the phone and email hidden in the form until Change, which takes a new one', async () => {
    const calls = fakeApi([makeCustomer(1, 'Nur', { lastName: 'Aisyah', phone: '+60112345678', email: 'nur.aisyah@example.com' })])
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await user.click(await list().findByRole('button', { name: /Nur/ }))
    expect(within(list().getByRole('button', { name: /Nur/ })).getByText('+60 11•••5678')).toBeInTheDocument()
    await user.click(await detail().findByRole('button', { name: 'Edit Customer Details' }))
    const form = screen.getByRole('dialog', { name: 'Edit Customer' })
    expect(within(form).getByText('+60 11•••5678')).toBeInTheDocument()
    expect(within(form).getByText('nu•••@example.com')).toBeInTheDocument()
    expect(within(form).queryByDisplayValue(/2345678/)).not.toBeInTheDocument()

    // Change starts an empty number; "Keep" goes back to the hidden one.
    await user.click(within(form).getByRole('button', { name: 'Change phone number' }))
    expect(within(form).getByRole('button', { name: 'Save' })).toBeDisabled()
    await user.click(within(form).getByRole('button', { name: 'Keep the current phone number' }))
    await user.click(within(form).getByRole('button', { name: 'Change email' }))
    await user.type(within(form).getByRole('textbox', { name: 'Email' }), 'nur@example.com')
    await user.click(within(form).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(calls.some((call) => call.method === 'PUT')).toBe(true))
    expect(calls.find((call) => call.method === 'PUT')!.body).toMatchObject({ phone: '+60112345678', email: 'nur@example.com' })
  })

  it('deletes a customer after confirmation', async () => {
    const calls = fakeApi([makeCustomer(1, 'Nur')])
    const user = userEvent.setup()
    render(<CustomersScreen onOpenMenu={() => {}} />)

    await user.click(await list().findByRole('button', { name: /Nur/ }))
    await user.click(await detail().findByRole('button', { name: 'Edit Customer Details' }))
    await user.click(screen.getByRole('button', { name: 'Delete customer' }))
    expect(calls.some((call) => call.method === 'DELETE')).toBe(false)
    await user.click(screen.getByRole('button', { name: 'Confirm delete' }))

    expect(await screen.findByText('Select a customer')).toBeInTheDocument()
    expect(await list().findByText('No customers yet.')).toBeInTheDocument()
    expect(calls.some((call) => call.method === 'DELETE' && call.url.pathname === '/api/v1/customers/1')).toBe(true)
  })
})
