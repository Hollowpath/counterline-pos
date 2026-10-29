import { render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthToken } from '../api/http'
import { DashboardApp } from './DashboardApp'
import type { LiveSales, SalesOverview } from './types'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const outlets = [
  { id: 1, code: 'O1', name: 'Counterline Bukit Bintang', address: null },
  { id: 3, code: 'O2', name: 'Counterline Cheras', address: null },
]

const live = (forOutlets: typeof outlets): LiveSales => ({
  date: '2026-10-13',
  orderCount: 3,
  netSales: 61,
  generatedAt: '2026-10-13T02:00:00Z',
  outlets: forOutlets.map((outlet, index) => ({
    outletId: outlet.id, code: outlet.code, name: outlet.name, registerOpen: index === 0, openOrders: 0,
    orderCount: index === 0 ? 2 : 1, grossSales: index === 0 ? 32 : 29, netSales: index === 0 ? 32 : 29,
    lastSaleAt: '2026-10-13T01:30:00Z',
    recentOrders: [{ orderNumber: `R${10 + index}`, paidAt: '2026-10-13T01:30:00Z', total: 16, staffName: 'Aisyah Rahman', items: '1× WSP Caramel' }],
  })),
})

const figures = (netSales: number, orders: number) => ({
  netSales, orders, averageOrder: orders === 0 ? 0 : netSales / orders, itemsSold: orders * 2, memberOrders: 1, newMembers: 1,
  freeItems: 2, freeItemsValue: 13, discounts: 1.6, refundCount: 0, refunds: 0,
})

const overview = (forOutlets: typeof outlets): SalesOverview => ({
  from: '2026-10-07', to: '2026-10-13', outletId: null,
  current: figures(61, 3),
  previous: figures(50, 2),
  days: Array.from({ length: 7 }, (_, index) => ({ date: `2026-10-${String(7 + index).padStart(2, '0')}`, orders: 1, netSales: 10 + index })),
  outlets: forOutlets.map((outlet, index) => ({ outletId: outlet.id, name: outlet.name, orders: 2 - index, netSales: 32 - index * 3 })),
  hours: Array.from({ length: 24 }, (_, hour) => ({ hour, orders: hour === 15 ? 3 : 0, sales: hour === 15 ? 61 : 0 })),
  topItems: [{ name: 'WSP Caramel', quantity: 4, sales: 64 }],
  categories: [{ name: 'White Stand Pouch', amount: 64 }],
  paymentMethods: [{ name: 'Visa', amount: 45 }, { name: 'Cash', amount: 16 }],
  promotions: [{ name: 'Buy 3 Free 1 Promo', count: 2, amount: 13 }],
  discounts: [{ name: 'Staff 10%', count: 1, amount: 1.6 }],
  generatedAt: '2026-10-13T02:00:00Z',
})

function stubApi({ emptyCatalogue = false } = {}) {
  const calls: { key: string; body: unknown }[] = []
  // The catalogue is kept as state so folders and items added through the API show on the next read.
  const catalogue: { id: number; name: string; tab: string | null; items: Record<string, unknown>[] }[] = emptyCatalogue ? [] : [
    { id: 1, name: 'White Stand Pouch', tab: 'Selling Items', items: [
      { id: 7, name: 'WSP Caramel', price: 16, active: true, promotion: null, seasonal: false, seasonalFrom: null, seasonalUntil: null, seasonalNow: false },
      { id: 8, name: 'B3 F1 BC Caramel', price: 6.5, active: true, promotion: 'Buy 3 Free 1 Promo', seasonal: false, seasonalFrom: null,
        seasonalUntil: null, seasonalNow: false },
    ] },
  ]
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://pos.test')
    const key = `${init.method ?? 'GET'} ${url.pathname}`
    const body = init.body ? JSON.parse(String(init.body)) : null
    calls.push({ key, body })
    const manager = new Headers(init.headers).get('Authorization') === 'Bearer manager-jwt'
    switch (key) {
      case 'POST /api/v1/dashboard/auth/login':
        if (body.password !== 'correct horse') return json({ title: 'Login failed', detail: 'Wrong email or password' }, 401)
        // Head office and managers alike log in with email and password.
        return body.email === 'meiling@counterline.test'
          ? json({ token: 'manager-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Mei Ling Tan', role: 'MANAGER', outlet: outlets[1] })
          : json({ token: 'admin-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Head Office', role: 'ADMIN', outlet: null })
      case 'GET /api/v1/dashboard/live':
        return json(live(manager ? [outlets[1]] : outlets))
      case 'GET /api/v1/dashboard/overview':
        return json(overview(manager ? [outlets[1]] : outlets))
      case 'GET /api/v1/dashboard/outlets':
        return json(manager ? [outlets[1]] : outlets)
      case 'GET /api/v1/dashboard/catalog/items':
        return json(catalogue)
      case 'POST /api/v1/dashboard/catalog/folders': {
        const folder = { id: catalogue.length + 1, name: body.name, tab: body.tab, items: [] }
        catalogue.push(folder)
        return json(folder, 201)
      }
      case 'POST /api/v1/dashboard/catalog/items': {
        const item = { id: 100, name: body.name, price: body.price, active: true, promotion: null, seasonal: false, seasonalFrom: null,
          seasonalUntil: null, seasonalNow: false }
        catalogue.find((category) => category.id === body.categoryId)!.items.push(item)
        return json(item, 201)
      }
      case 'PUT /api/v1/dashboard/catalog/items/7':
        return json({ id: 7, ...body, promotion: null, seasonalNow: body.seasonal })
      case 'GET /api/v1/dashboard/discounts':
        return json([{ id: 1, name: 'Staff Discount', kind: 'PERCENT', value: 30, scope: 'BOTH', requiresApproval: false, active: true }])
      case 'POST /api/v1/dashboard/discounts':
        return json({ id: 2, ...body }, 201)
      default:
        if (url.pathname.startsWith('/api/v1/dashboard/outlets/')) {
          return json({ from: '2026-10-13', to: '2026-10-13', orderCount: 1, grossSales: 29, discounts: 0, refundCount: 0, refunds: 0,
            netSales: 29, paymentMethods: [], totalCollected: 29, orderStatuses: [], diningOptions: [] })
        }
        return json({ title: 'Not found' }, 404)
    }
  }))
  return calls
}

async function logIn(user: UserEvent, email: string) {
  render(<DashboardApp />)
  await user.type(screen.getByLabelText('Email'), email)
  await user.type(screen.getByLabelText('Password'), 'correct horse')
  await user.click(screen.getByRole('button', { name: 'Log in' }))
}

const logInAsHeadOffice = (user: UserEvent) => logIn(user, 'admin@counterline.test')

const nav = () => within(screen.getByRole('navigation', { name: 'Dashboard' }))

beforeEach(() => sessionStorage.clear())
afterEach(() => setAuthToken(null))

describe('back-office dashboard', () => {
  it('head office logs in with email and password, sees the charts, and every outlet live', async () => {
    stubApi()
    const user = userEvent.setup()
    render(<DashboardApp />)
    await user.type(screen.getByLabelText('Email'), 'admin@counterline.test')
    await user.type(screen.getByLabelText('Password'), 'wrong')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong email or password')
    await user.clear(screen.getByLabelText('Password'))
    await user.type(screen.getByLabelText('Password'), 'correct horse')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    // The overview first: headline figures against the period before, and the charts.
    const sales = await screen.findByRole('region', { name: 'Sales overview' })
    expect((await within(sales).findByText('Net sales')).closest('div')).toHaveTextContent('Net salesRM61.00')
    expect(within(sales).getByText('▲ 22% vs the 7 days before')).toBeInTheDocument()
    expect(within(sales).getByRole('img', { name: 'Net sales by day' })).toBeInTheDocument()
    expect(within(sales).getByRole('list', { name: 'Net sales by outlet' })).toHaveTextContent(/Counterline Bukit Bintang.*Counterline Cheras/)
    expect(within(sales).getByRole('list', { name: 'Top items by units sold' })).toHaveTextContent('WSP Caramel')
    expect(within(sales).getByRole('list', { name: 'Collected by payment method' })).toHaveTextContent(/Visa74%RM45.00.*Cash26%RM16.00/)
    // What the promotions and discounts gave away, beside the sales.
    expect(within(sales).getByText('Free items').closest('div')).toHaveTextContent('Free items2worth RM13.00')
    expect(within(sales).getByText('Member orders').closest('div')).toHaveTextContent('1 of 3 orders · 1 new member')
    expect(within(sales).getByRole('list', { name: 'Free items by promotion' })).toHaveTextContent('Buy 3 Free 1 Promoworth RM13.002 free')
    expect(within(sales).getByRole('list', { name: 'Discounts and vouchers used' })).toHaveTextContent('Staff 10%× 1RM1.60')
    expect(within(sales).getByText(/^Best day:/)).toHaveTextContent('Best day: 13 Oct · RM16.00 · 1 order')
    expect(within(sales).getByText(/^Busiest:/)).toHaveTextContent('Busiest: 3pm · RM61.00 · 3 orders')

    await user.click(nav().getByRole('button', { name: 'Live Sales' }))
    expect(await screen.findByRole('article', { name: 'Counterline Cheras' })).toBeInTheDocument()
    expect(screen.getByTestId('chain-net')).toHaveTextContent('RM61.00')
    const bukitBintang = screen.getByRole('article', { name: 'Counterline Bukit Bintang' })
    expect(bukitBintang).toHaveTextContent('Register open')
    expect(bukitBintang).toHaveTextContent('RM32.00')
    expect(bukitBintang).toHaveTextContent('R10 1× WSP Caramel')
    expect(nav().getByRole('button', { name: 'Items & Prices' })).toBeInTheDocument()
    expect(nav().getByText('Head office · all outlets')).toBeInTheDocument()
  })

  it('an outlet manager logs in with email and password and sees only their outlet, without the catalogue', async () => {
    stubApi()
    const user = userEvent.setup()
    render(<DashboardApp />)
    // No PIN login any more.
    expect(screen.queryByRole('tab', { name: 'Outlet manager' })).not.toBeInTheDocument()
    await user.type(screen.getByLabelText('Email'), 'meiling@counterline.test')
    await user.type(screen.getByLabelText('Password'), 'correct horse')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    const sales = await screen.findByRole('region', { name: 'Sales overview' })
    expect(await within(sales).findByText('Counterline Cheras', { exact: false })).toBeInTheDocument()
    expect(within(sales).queryByRole('combobox', { name: 'Outlet' })).not.toBeInTheDocument()
    await user.click(nav().getByRole('button', { name: 'Live Sales' }))
    expect(await screen.findByRole('article', { name: 'Counterline Cheras' })).toBeInTheDocument()
    expect(screen.queryByRole('article', { name: 'Counterline Bukit Bintang' })).not.toBeInTheDocument()
    expect(nav().queryByRole('button', { name: 'Items & Prices' })).not.toBeInTheDocument()
    expect(nav().queryByRole('button', { name: 'Discounts' })).not.toBeInTheDocument()
    expect(nav().getByText('Manager · Counterline Cheras')).toBeInTheDocument()
  })

  it('head office changes a price for every till', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))

    const price = await screen.findByLabelText('Price of WSP Caramel')
    const save = within(price.closest('tr')!).getByRole('button', { name: 'Save' })
    expect(save).toBeDisabled()
    expect(screen.getByText('Free with Buy 3 Free 1 Promo')).toBeInTheDocument()
    await user.clear(price)
    await user.type(price, '17.50')
    await user.click(save)

    expect(await screen.findByText('Saved')).toBeInTheDocument()
    expect(calls.find((call) => call.key === 'PUT /api/v1/dashboard/catalog/items/7')!.body)
      .toEqual({ name: 'WSP Caramel', price: 17.5, active: true, seasonal: false, seasonalFrom: null, seasonalUntil: null })
  })

  it('head office builds an empty catalogue: adds a folder, then its first item', async () => {
    const calls = stubApi({ emptyCatalogue: true })
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))

    expect(await screen.findByText(/There are no folders yet/)).toBeInTheDocument()
    expect(screen.queryByLabelText('New item name')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add folder' })).toBeDisabled()

    await user.type(screen.getByLabelText('Folder name'), 'White Stand Pouch')
    expect(screen.getByLabelText('Tab')).toHaveValue('Selling Items')
    await user.click(screen.getByRole('button', { name: 'Add folder' }))

    expect(await screen.findByText('Added White Stand Pouch to Selling Items')).toBeInTheDocument()
    expect(calls.find((call) => call.key === 'POST /api/v1/dashboard/catalog/folders')!.body)
      .toEqual({ name: 'White Stand Pouch', tab: 'Selling Items' })
    // The empty folder is listed, with the row for its first item.
    const folder = await screen.findByRole('region', { name: 'White Stand Pouch' })
    expect(screen.queryByText(/There are no folders yet/)).not.toBeInTheDocument()
    await user.type(within(folder).getByLabelText('New item name'), 'WSP Caramel')
    await user.type(within(folder).getByLabelText('New item price'), '16')
    await user.click(within(folder).getByRole('button', { name: 'Add' }))

    expect(await screen.findByLabelText('Name of WSP Caramel')).toBeInTheDocument()
    expect(calls.find((call) => call.key === 'POST /api/v1/dashboard/catalog/items')!.body)
      .toEqual({ categoryId: 1, name: 'WSP Caramel', price: 16 })
  })

  it('keeps the Add folder form available once the catalogue has folders', async () => {
    stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))

    // Available with a catalogue too, defaulting to an existing tab.
    expect(await screen.findByLabelText('Tab')).toHaveValue('Selling Items')
    expect(screen.queryByText(/There are no folders yet/)).not.toBeInTheDocument()
  })

  it('head office makes an item a seasonal flavour until its last day', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))

    await user.click(await screen.findByLabelText('WSP Caramel is a seasonal flavour'))
    const row = screen.getByLabelText('Price of WSP Caramel').closest('tr')!
    // A seasonal flavour is for a limited time: no saving without its last day.
    expect(within(row).getByRole('button', { name: 'Save' })).toBeDisabled()
    await user.type(within(row).getByLabelText('WSP Caramel seasonal until'), '2026-12-31')
    await user.click(within(row).getByRole('button', { name: 'Save' }))

    expect(await within(row).findByText('Saved')).toBeInTheDocument()
    expect(calls.find((call) => call.key === 'PUT /api/v1/dashboard/catalog/items/7')!.body)
      .toMatchObject({ seasonal: true, seasonalFrom: null, seasonalUntil: '2026-12-31' })
    expect(within(row).getByText(/In season until/)).toBeInTheDocument()
  })

  it('head office adds a discount that needs a manager PIN', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Discounts' }))

    await user.type(await screen.findByLabelText('Name of new discount'), 'Opening Week 15%')
    await user.clear(screen.getByLabelText('Value of new discount'))
    await user.type(screen.getByLabelText('Value of new discount'), '15')
    await user.selectOptions(screen.getByLabelText('Where new discount is offered'), 'ORDER')
    await user.click(screen.getByLabelText('new discount needs a manager PIN'))
    await user.click(screen.getByRole('button', { name: 'Add' }))

    expect(calls.find((call) => call.key === 'POST /api/v1/dashboard/discounts')!.body).toMatchObject({
      name: 'Opening Week 15%', kind: 'PERCENT', value: 15, scope: 'ORDER', requiresApproval: true,
    })
  })

  it('shows any outlet’s reports to head office', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Reports' }))

    await user.selectOptions(await screen.findByLabelText('Outlet'), '3')
    expect(await screen.findByText('Net Sales')).toBeInTheDocument()
    expect(calls.some((call) => call.key === 'GET /api/v1/dashboard/outlets/3/reports/sales-summary')).toBe(true)
  })
})
