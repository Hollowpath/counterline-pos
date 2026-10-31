import { render, screen, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthToken } from '../api/http'
import { DashboardApp } from './DashboardApp'
import { answerConfirm } from '../test/confirmDialog'
import type { LiveSales, SalesOverview } from './types'

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const outlets = [
  { id: 1, code: 'O1', name: 'Counterline Bukit Bintang', address: null, closed: false },
  { id: 3, code: 'O2', name: 'Counterline Cheras', address: null, closed: false },
]

// An outlet closed since: it is in history (overview, reports) but not in live status.
const closedOutlet = { id: 7, code: 'OLD', name: 'Counterline Old', address: null, closed: true }

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
  outlets: forOutlets.map((outlet, index) => ({ outletId: outlet.id, name: outlet.name, orders: 2 - index, netSales: 32 - index * 3, closed: outlet.closed })),
  hours: Array.from({ length: 24 }, (_, hour) => ({ hour, orders: hour === 15 ? 3 : 0, sales: hour === 15 ? 61 : 0 })),
  topItems: [{ name: 'WSP Caramel', quantity: 4, sales: 64 }],
  categories: [{ name: 'White Stand Pouch', amount: 64 }],
  paymentMethods: [{ name: 'Visa', amount: 45 }, { name: 'Cash', amount: 16 }],
  promotions: [{ name: 'Buy 3 Free 1 Promo', count: 2, amount: 13 }],
  discounts: [{ name: 'Staff 10%', count: 1, amount: 1.6 }],
  generatedAt: '2026-10-13T02:00:00Z',
})

function stubApi({ emptyCatalogue = false, closedOutletInHistory = false, onlyClosedOutlet = false, changedElsewhere = false, folderChangedElsewhere = false, folderCollision = false, itemCollision = false,
  orderRefusal = null as null | 'stale' | 'incomplete' } = {}) {
  // Head office's history also has the outlet closed since; live status and a manager's view do not.
  // The closed one first, as a list by name can have it: the screens must not open on it just for that.
  const historyOutlets = onlyClosedOutlet ? [closedOutlet] : closedOutletInHistory ? [closedOutlet, ...outlets] : outlets
  let folderSavedElsewhere = false
  let orderRefused = false
  // changedElsewhere: another admin saves item 7 first, so the first edit of it is refused (409) and a reload shows theirs.
  let elsewhereSaved = false
  const calls: { key: string; body: unknown }[] = []
  // The catalogue is kept as state so folders and items added through the API show on the next read.
  const catalogue: { id: number; version?: number; name: string; tab: string | null; row?: number; col?: number; countsForPromotions?: boolean;
    items: Record<string, unknown>[] }[] = emptyCatalogue ? [] : [
    { id: 1, version: 5, name: 'White Stand Pouch', tab: 'Selling Items', row: 1, col: 1, countsForPromotions: true, items: [
      { id: 7, name: 'WSP Caramel', price: 16, active: true, promotion: null, seasonal: false, seasonalFrom: null, seasonalUntil: null, seasonalNow: false, version: 3 },
      { id: 8, name: 'B3 F1 BC Caramel', price: 6.5, active: true, promotion: 'Buy 3 Free 1 Promo', seasonal: false, seasonalFrom: null,
        seasonalUntil: null, seasonalNow: false, version: 4 },
    ] },
    { id: 2, version: 6, name: 'Empty Folder', tab: 'Selling Items', row: 1, col: 2, countsForPromotions: false, items: [] },
  ]
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://pos.test')
    const key = `${init.method ?? 'GET'} ${url.pathname}`
    const body = init.body ? JSON.parse(String(init.body)) : null
    calls.push({ key, body })
    if (key === 'PUT /api/v1/dashboard/catalog/folders/1' && body.name === 'Boom') {
      return json({ title: 'Conflict', detail: 'A folder named Boom already exists' }, 409)
    }
    const manager = new Headers(init.headers).get('Authorization') === 'Bearer manager-jwt'
    switch (key) {
      case 'POST /api/v1/dashboard/auth/login':
        if (body.password !== 'correct horse') return json({ title: 'Login failed', detail: 'Wrong email or password' }, 401)
        // Head office and managers alike log in with email and password.
        return body.email === 'meiling@counterline.test'
          ? json({ token: 'manager-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Mei Ling Tan', role: 'MANAGER', outlet: outlets[1] })
          : json({ token: 'admin-jwt', expiresAt: '2999-01-01T00:00:00Z', name: 'Head Office', role: 'ADMIN', outlet: null })
      case 'POST /api/v1/dashboard/auth/logout':
        return new Response(null, { status: 204 })
      case 'GET /api/v1/dashboard/live':
        return json(live(manager ? [outlets[1]] : outlets))
      case 'GET /api/v1/dashboard/overview':
        return json(overview(manager ? [outlets[1]] : historyOutlets))
      case 'GET /api/v1/dashboard/outlets':
        return json(manager ? [outlets[1]] : url.searchParams.get('includeClosed') === 'true' ? historyOutlets : outlets)
      case 'GET /api/v1/dashboard/catalog/items':
        return json(catalogue)
      case 'POST /api/v1/dashboard/catalog/folders': {
        const folder = { id: catalogue.length + 1, name: body.name, tab: body.tab, row: 1, col: 1, countsForPromotions: false, items: [] }
        catalogue.push(folder)
        return json(folder, 201)
      }
      case 'GET /api/v1/dashboard/catalog/tabs':
        return json([{ id: 1, name: 'Selling Items', sortOrder: 1, folders: 2, version: 7 }, { id: 2, name: 'Gifts', sortOrder: 2, folders: 0, version: 8 }])
      case 'PUT /api/v1/dashboard/catalog/tabs/1':
        // Folders on the renamed tab now name the new one.
        for (const folder of catalogue) {
          if (folder.tab === 'Selling Items') folder.tab = body.name
        }
        return json({ id: 1, name: body.name, sortOrder: 1, folders: 2, version: body.version + 1 })
      case 'PUT /api/v1/dashboard/catalog/tabs/order':
        return json([])
      case 'DELETE /api/v1/dashboard/catalog/tabs/2':
      case 'DELETE /api/v1/dashboard/catalog/folders/2':
        return new Response(null, { status: 204 })
      case 'PUT /api/v1/dashboard/catalog/folders/1':
        if (folderCollision && !folderSavedElsewhere) {
          folderSavedElsewhere = true
          Object.assign(catalogue[0], { name: 'Pouches (theirs)' })
          return json({ title: 'Conflict', detail: 'That change collided with another one, or with a sale. Reload and try again.' }, 409)
        }
        if (folderChangedElsewhere && !folderSavedElsewhere) {
          folderSavedElsewhere = true
          Object.assign(catalogue[0], { name: 'Pouches (theirs)', version: 6 })
          return json({ title: 'Conflicting change', detail: 'Changed elsewhere. Reload.' }, 409)
        }
        return json({ id: 1, name: body.name, tab: body.tab, row: null, col: null, countsForPromotions: body.countsForPromotions, items: [], version: body.version + 1 })
      case 'PUT /api/v1/dashboard/catalog/folders/1/items/order':
        if (orderRefusal && !orderRefused) {
          orderRefused = true
          if (orderRefusal === 'stale') {
            Object.assign(catalogue[0].items[1], { name: 'B3 F1 BC Caramel (theirs)', version: 9 })
            return json({ title: 'Conflicting change', detail: 'Changed elsewhere. Reload.' }, 409)
          }
          return json({ title: 'Bad request', detail: 'The order must list every one of the 3 items exactly once' }, 400)
        }
        return json([])
      case 'PUT /api/v1/dashboard/catalog/modifier-groups/5/options/order':
        return json([])
      case 'DELETE /api/v1/dashboard/catalog/items/7':
        return json({ outcome: 'DEACTIVATED' })
      case 'DELETE /api/v1/dashboard/catalog/items/8':
        return json({ outcome: 'DELETED' })
      case 'GET /api/v1/dashboard/catalog/items/7/modifier-groups':
        return json([{ id: 5, itemId: 7, name: 'Flavours', chooseExactly: 3, options: [
          { id: 11, name: 'Caramel', price: 0, active: true, version: 9 }, { id: 12, name: 'Matcha', price: 2, active: true, version: 10 }],
          version: 2 }])
      case 'PUT /api/v1/dashboard/catalog/modifier-groups/5':
        return json({ id: 5, itemId: 7, options: [], ...body, version: body.version + 1 })
      case 'PUT /api/v1/dashboard/catalog/modifier-options/12':
        return json({ id: 12, ...body, version: body.version + 1 })
      case 'POST /api/v1/dashboard/catalog/items/7/modifier-groups':
      case 'POST /api/v1/dashboard/catalog/modifier-groups/5/options':
        return json({ id: 99, ...body }, 201)
      case 'DELETE /api/v1/dashboard/catalog/modifier-options/12':
        return json({ outcome: 'DEACTIVATED' })
      case 'DELETE /api/v1/dashboard/catalog/modifier-groups/5':
        return json({ outcome: 'DELETED' })
      case 'POST /api/v1/dashboard/catalog/items': {
        const item = { id: 100, name: body.name, price: body.price, active: true, promotion: null, seasonal: false, seasonalFrom: null,
          seasonalUntil: null, seasonalNow: false, version: 0 }
        catalogue.find((category) => category.id === body.categoryId)!.items.push(item)
        return json(item, 201)
      }
      case 'PUT /api/v1/dashboard/catalog/items/7': {
        if (body.name === 'Dup') {
          return json({ title: 'Conflict', detail: 'White Stand Pouch already has an item called Dup' }, 409)
        }
        if (itemCollision && !elsewhereSaved) {
          elsewhereSaved = true
          Object.assign(catalogue[0].items[0], { name: 'WSP Caramel (theirs)' })
          return json({ title: 'Conflict', detail: 'That change collided with another one, or with a sale. Reload and try again.' }, 409)
        }
        if (changedElsewhere && !elsewhereSaved) {
          elsewhereSaved = true
          Object.assign(catalogue[0].items[0], { name: 'WSP Caramel (renamed)', price: 18, version: 4 })
          return json({ title: 'Conflicting change', detail: 'Changed elsewhere. Reload.' }, 409)
        }
        return json({ id: 7, ...body, promotion: null, seasonalNow: body.seasonal, version: body.version + 1 })
      }
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
    // The rankings count what was sold, so they say so; the net and payment figures do not.
    for (const title of ['Busiest hours', 'Top items', 'Sales by folder', 'Free items given', 'Discounts & vouchers']) {
      expect(within(sales).getByRole('heading', { name: title }).parentElement).toHaveTextContent('Before refunds')
    }
    expect(within(sales).getAllByText('Before refunds')).toHaveLength(5)
    expect(within(sales).getByRole('heading', { name: 'Payment methods' }).parentElement).not.toHaveTextContent('Before refunds')

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

  it('logging off ends the session on the server too, with the token it was using, and then shows the login', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await screen.findByRole('region', { name: 'Sales overview' })

    await user.click(screen.getByRole('button', { name: 'Log Off' }))

    expect(await screen.findByRole('button', { name: 'Log in' })).toBeInTheDocument()
    expect(calls.filter((call) => call.key === 'POST /api/v1/dashboard/auth/logout')).toHaveLength(1)
    const fetchMock = vi.mocked(fetch)
    const logout = fetchMock.mock.calls.find(([input]) => String(input) === '/api/v1/dashboard/auth/logout')!
    expect(new Headers(logout[1]!.headers).get('Authorization')).toBe('Bearer admin-jwt')
    expect(sessionStorage.length).toBe(0)
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

  it('the overview and the reports keep an outlet closed since, marked closed', async () => {
    stubApi({ closedOutletInHistory: true })
    const user = userEvent.setup()
    await logInAsHeadOffice(user)

    const sales = await screen.findByRole('region', { name: 'Sales overview' })
    const picker = within(sales).getByRole('combobox', { name: 'Outlet' })
    expect(within(picker).getByRole('option', { name: 'Counterline Old (closed)' })).toBeInTheDocument()
    expect(within(picker).getByRole('option', { name: 'Counterline Cheras' })).toBeInTheDocument()
    // Its sales are in the chart, labelled as closed.
    expect(within(await screen.findByLabelText('Net sales by outlet')).getByText('Counterline Old (closed)')).toBeInTheDocument()

    await user.click(await nav().findByRole('button', { name: 'Reports' }))
    const reportPicker = await screen.findByLabelText('Outlet')
    expect(within(reportPicker).getByRole('option', { name: 'Counterline Old (closed)' })).toBeInTheDocument()
  })

  it('opens the reports on an open outlet even when a closed one comes first, and on the closed one only if it is all there is', async () => {
    stubApi({ closedOutletInHistory: true })
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Reports' }))

    const picker = await screen.findByLabelText('Outlet')
    expect(picker).toHaveValue('1')
    expect(screen.getByText("The till's reports for Counterline Bukit Bintang, for any day or dates.")).toBeInTheDocument()
    // The closed outlet is still one pick away.
    await user.selectOptions(picker, '7')
    expect(await screen.findByText("The till's reports for Counterline Old, for any day or dates.")).toBeInTheDocument()
  })

  it('opens the reports on the closed outlet when every outlet is closed', async () => {
    stubApi({ onlyClosedOutlet: true })
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Reports' }))

    expect(await screen.findByText("The till's reports for Counterline Old, for any day or dates.")).toBeInTheDocument()
  })

  it('shows no closed-outlet marks when every outlet is open', async () => {
    stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)

    const sales = await screen.findByRole('region', { name: 'Sales overview' })
    expect(within(sales).queryByText(/\(closed\)/)).not.toBeInTheDocument()
  })

  it('head office changes a price for every till', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))

    const price = await screen.findByLabelText('Price of WSP Caramel')
    const save = within(price.closest('tr')!).getByRole('button', { name: 'Save WSP Caramel' })
    expect(save).toBeDisabled()
    expect(screen.getByText('Free with Buy 3 Free 1 Promo')).toBeInTheDocument()
    await user.clear(price)
    await user.type(price, '17.50')
    await user.click(save)

    expect(await screen.findByText('Saved')).toBeInTheDocument()
    expect(calls.find((call) => call.key === 'PUT /api/v1/dashboard/catalog/items/7')!.body)
      .toEqual({ name: 'WSP Caramel', price: 17.5, active: true, seasonal: false, seasonalFrom: null, seasonalUntil: null, version: 3 })
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

  describe('restructuring the catalogue', () => {
    const openItems = async (user: UserEvent) => {
      await logInAsHeadOffice(user)
      await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))
      await screen.findByLabelText('Price of WSP Caramel')
    }
    const sent = (calls: { key: string; body: unknown }[], key: string) => calls.filter((call) => call.key === key)

    it('renames a folder and takes it off the till grid only after confirming', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const name = screen.getByLabelText('Name of folder White Stand Pouch')
      await user.clear(name)
      await user.type(name, 'Pouches')
      await user.click(screen.getByLabelText('White Stand Pouch counts for promotions'))
      await user.click(screen.getByRole('button', { name: 'Save folder White Stand Pouch' }))
      expect(await screen.findByText('Saved Pouches')).toBeInTheDocument()
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1')[0].body)
        .toEqual({ name: 'Pouches', tab: 'Selling Items', countsForPromotions: false, version: 5 })

      // Declined: nothing is sent. Accepted: the folder leaves the grid (tab null).
      await user.click(await screen.findByRole('button', { name: 'Remove White Stand Pouch from the till grid' }))
      await answerConfirm(user, 'Remove White Stand Pouch from the till grid?', 'Cancel')
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1')).toHaveLength(1)
      await user.click(screen.getByRole('button', { name: 'Remove White Stand Pouch from the till grid' }))
      await answerConfirm(user, 'Remove White Stand Pouch from the till grid?', 'Remove from grid')
      expect(await screen.findAllByText(/Saved/)).not.toHaveLength(0)
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1')[1].body).toMatchObject({ tab: null })
    })

    it('shows a success notice in the green result banner and a failure in the red one, as the other pages do', async () => {
      stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const name = screen.getByLabelText('Name of folder White Stand Pouch')
      await user.clear(name)
      await user.type(name, 'Boom')
      await user.click(screen.getByRole('button', { name: 'Save folder White Stand Pouch' }))
      const failure = await screen.findByText('A folder named Boom already exists')
      expect(failure).toHaveAttribute('role', 'alert')
      expect(failure).toHaveClass('bg-red-50', 'text-red-700')

      await user.clear(screen.getByLabelText('Name of folder White Stand Pouch'))
      await user.type(screen.getByLabelText('Name of folder White Stand Pouch'), 'Pouches')
      await user.click(screen.getByRole('button', { name: 'Save folder White Stand Pouch' }))
      const success = await screen.findByText('Saved Pouches')
      expect(success).toHaveAttribute('role', 'status')
      expect(success).toHaveClass('bg-green-50', 'text-green-700')
      expect(screen.queryByText('A folder named Boom already exists')).not.toBeInTheDocument()
    })

    it('deletes only an empty folder, and only when confirmed', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      expect(screen.getByRole('button', { name: 'Delete folder White Stand Pouch' })).toBeDisabled()
      await user.click(screen.getByRole('button', { name: 'Delete folder Empty Folder' }))
      await answerConfirm(user, 'Delete the empty folder Empty Folder?', 'Cancel')
      expect(sent(calls, 'DELETE /api/v1/dashboard/catalog/folders/2')).toHaveLength(0)
      await user.click(screen.getByRole('button', { name: 'Delete folder Empty Folder' }))
      await answerConfirm(user, 'Delete the empty folder Empty Folder?', 'Delete folder')
      expect(await screen.findByText('Deleted Empty Folder')).toBeInTheDocument()
      expect(sent(calls, 'DELETE /api/v1/dashboard/catalog/folders/2')).toHaveLength(1)
    })

    it('renames, reorders and deletes tabs; a tab with folders cannot be deleted', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const tabs = await screen.findByRole('region', { name: 'Tabs' })
      expect(within(tabs).getByRole('button', { name: 'Delete tab Selling Items' })).toBeDisabled()
      const rename = within(tabs).getByLabelText('Name of tab Gifts')
      await user.clear(rename)
      await user.type(rename, 'Gift Boxes')
      expect(within(tabs).getByRole('button', { name: 'Save tab Gifts' })).toBeEnabled()
      await user.click(within(tabs).getByRole('button', { name: 'Move tab Gifts earlier' }))
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/tabs/order')[0].body).toEqual({ ids: [2, 1], versions: [8, 7] })

      await user.click(within(await screen.findByRole('region', { name: 'Tabs' })).getByRole('button', { name: 'Delete tab Gifts' }))
      await answerConfirm(user, 'Delete the Gifts tab?', 'Cancel')
      expect(sent(calls, 'DELETE /api/v1/dashboard/catalog/tabs/2')).toHaveLength(0)
      await user.click(within(await screen.findByRole('region', { name: 'Tabs' })).getByRole('button', { name: 'Delete tab Gifts' }))
      await answerConfirm(user, 'Delete the Gifts tab?', 'Delete tab')
      await vi.waitFor(() => expect(sent(calls, 'DELETE /api/v1/dashboard/catalog/tabs/2')).toHaveLength(1))
    })

    it('removes an item after confirming: a sold one is only taken off sale, an unsold one is deleted', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      await user.click(screen.getByRole('button', { name: 'Remove WSP Caramel' }))
      await answerConfirm(user, 'Remove WSP Caramel?', 'Cancel')
      expect(sent(calls, 'DELETE /api/v1/dashboard/catalog/items/7')).toHaveLength(0)
      await user.click(screen.getByRole('button', { name: 'Remove WSP Caramel' }))
      await answerConfirm(user, 'Remove WSP Caramel?', 'Remove item')
      expect(await screen.findByText('WSP Caramel was sold before: taken off sale, with its history kept')).toBeInTheDocument()

      await user.click(await screen.findByRole('button', { name: 'Remove B3 F1 BC Caramel' }))
      await answerConfirm(user, 'Remove B3 F1 BC Caramel?', 'Remove item')
      expect(await screen.findByText('Deleted B3 F1 BC Caramel')).toBeInTheDocument()
    })

    it('gives every item row its own Save button, named after the item', async () => {
      stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const table = screen.getByLabelText('Price of WSP Caramel').closest('table')!
      const saves = within(table).getAllByRole('button', { name: /^Save / })
      const names = saves.map((button) => button.getAttribute('aria-label'))
      expect(names).toEqual(expect.arrayContaining(['Save WSP Caramel', 'Save B3 F1 BC Caramel']))
      expect(new Set(names).size).toBe(names.length)
      // No Save button in the table is left with only the word "Save" to say what it saves.
      expect(within(table).queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    })

    it('reorders an item and moves it to another folder', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      expect(screen.getByRole('button', { name: 'Move WSP Caramel up' })).toBeDisabled()
      await user.click(screen.getByRole('button', { name: 'Move WSP Caramel down' }))
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1/items/order')[0].body).toEqual({ ids: [8, 7], versions: [4, 3] })

      await user.selectOptions(await screen.findByLabelText('Folder of WSP Caramel'), 'Empty Folder')
      expect(await screen.findByText('Moved WSP Caramel to Empty Folder')).toBeInTheDocument()
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/items/7')[0].body)
        .toMatchObject({ categoryId: 2, name: 'WSP Caramel', price: 16, active: true, version: 3 })
    })

    it('carries the version a tab, group and option were loaded at when saving them', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const tabs = await screen.findByRole('region', { name: 'Tabs' })
      const tabName = within(tabs).getByLabelText('Name of tab Selling Items')
      await user.clear(tabName)
      await user.type(tabName, 'Main')
      await user.click(within(tabs).getByRole('button', { name: 'Save tab Selling Items' }))
      await vi.waitFor(() => expect(sent(calls, 'PUT /api/v1/dashboard/catalog/tabs/1')[0].body).toEqual({ name: 'Main', version: 7 }))

      await user.click(screen.getByRole('button', { name: 'Options of WSP Caramel' }))
      const editor = await screen.findByRole('group', { name: 'Options of WSP Caramel' })
      const groupName = within(editor).getByLabelText('Name of group Flavours')
      await user.clear(groupName)
      await user.type(groupName, 'Picks')
      await user.click(within(editor).getByRole('button', { name: 'Save group Flavours' }))
      await vi.waitFor(() => expect(sent(calls, 'PUT /api/v1/dashboard/catalog/modifier-groups/5')[0].body)
        .toEqual({ name: 'Picks', chooseExactly: 3, version: 2 }))

      const matchaPrice = await within(await screen.findByRole('group', { name: 'Options of WSP Caramel' }))
        .findByLabelText('Extra price of option Matcha')
      await user.clear(matchaPrice)
      await user.type(matchaPrice, '2.50')
      await user.click(screen.getByRole('button', { name: 'Save option Matcha' }))
      await vi.waitFor(() => expect(sent(calls, 'PUT /api/v1/dashboard/catalog/modifier-options/12')[0].body)
        .toEqual({ name: 'Matcha', price: 2.5, active: true, version: 10 }))
    })

    it('an item someone else saved first is refused, and the row is rebuilt from their version', async () => {
      const calls = stubApi({ changedElsewhere: true })
      const user = userEvent.setup()
      await openItems(user)

      const price = screen.getByLabelText('Price of WSP Caramel')
      await user.clear(price)
      await user.type(price, '17.50')
      await user.click(screen.getByRole('button', { name: 'Save WSP Caramel' }))

      // The refusal is shown, nothing is claimed as saved, and the row now shows what the other admin saved.
      expect(await screen.findByText(/Changed elsewhere\. Reload\./)).toBeInTheDocument()
      expect(screen.queryByText('Saved')).not.toBeInTheDocument()
      expect(await screen.findByLabelText('Price of WSP Caramel (renamed)')).toHaveValue(18)
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/items/7')).toHaveLength(1)

      // Saving again from the rebuilt row carries the new version and goes through.
      const next = screen.getByLabelText('Price of WSP Caramel (renamed)')
      await user.clear(next)
      await user.type(next, '19')
      await user.click(screen.getByRole('button', { name: 'Save WSP Caramel (renamed)' }))
      expect(await screen.findByText('Saved')).toBeInTheDocument()
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/items/7')[1].body)
        .toMatchObject({ name: 'WSP Caramel (renamed)', price: 19, version: 4 })
    })

    it('rebuilds the folder toolbar from the newer folder after it was refused as changed elsewhere', async () => {
      const calls = stubApi({ folderChangedElsewhere: true })
      const user = userEvent.setup()
      await openItems(user)

      const name = screen.getByLabelText('Name of folder White Stand Pouch')
      await user.clear(name)
      await user.type(name, 'Mine')
      await user.click(screen.getByRole('button', { name: 'Save folder White Stand Pouch' }))

      // The refusal is shown and the toolbar now holds the other admin's folder, not what was typed here.
      expect(await screen.findByText('Changed elsewhere. Reload.')).toBeInTheDocument()
      expect(await screen.findByLabelText('Name of folder Pouches (theirs)')).toHaveValue('Pouches (theirs)')
      expect(screen.queryByDisplayValue('Mine')).not.toBeInTheDocument()

      // A save from the rebuilt toolbar carries the newer version.
      const next = screen.getByLabelText('Name of folder Pouches (theirs)')
      await user.clear(next)
      await user.type(next, 'Both')
      await user.click(screen.getByRole('button', { name: 'Save folder Pouches (theirs)' }))
      expect(await screen.findByText('Saved Both')).toBeInTheDocument()
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1')[1].body).toMatchObject({ name: 'Both', version: 6 })
    })

    it('reloads after an item could not be moved to another folder because someone else saved it', async () => {
      const calls = stubApi({ changedElsewhere: true })
      const user = userEvent.setup()
      await openItems(user)

      await user.selectOptions(screen.getByLabelText('Folder of WSP Caramel'), 'Empty Folder')
      expect(await screen.findByText(/Changed elsewhere\. Reload\./)).toBeInTheDocument()
      // The row is the other admin's version, in its old folder, ready for another attempt.
      expect(await screen.findByLabelText('Folder of WSP Caramel (renamed)')).toHaveValue('1')

      await user.selectOptions(screen.getByLabelText('Folder of WSP Caramel (renamed)'), 'Empty Folder')
      expect(await screen.findByText('Moved WSP Caramel (renamed) to Empty Folder')).toBeInTheDocument()
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/items/7')).toHaveLength(2)
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/items/7')[1].body).toMatchObject({ categoryId: 2, version: 4 })
    })

    it('a reorder carries the versions of every row, and a refused or incomplete one reloads the list', async () => {
      const stale = stubApi({ orderRefusal: 'stale' })
      const user = userEvent.setup()
      await openItems(user)

      await user.click(screen.getByRole('button', { name: 'Move WSP Caramel down' }))
      expect(await screen.findByText('Changed elsewhere. Reload.')).toBeInTheDocument()
      // The list is rebuilt from the newer rows, and the next reorder carries their versions.
      await user.click(await screen.findByRole('button', { name: 'Move WSP Caramel down' }))
      await vi.waitFor(() => expect(sent(stale, 'PUT /api/v1/dashboard/catalog/folders/1/items/order')).toHaveLength(2))
      expect(sent(stale, 'PUT /api/v1/dashboard/catalog/folders/1/items/order')[0].body).toEqual({ ids: [8, 7], versions: [4, 3] })
      expect(sent(stale, 'PUT /api/v1/dashboard/catalog/folders/1/items/order')[1].body).toEqual({ ids: [8, 7], versions: [9, 3] })
      expect(await screen.findByLabelText('Price of B3 F1 BC Caramel (theirs)')).toBeInTheDocument()
    })

    it('reloads the list when a reorder is refused as incomplete', async () => {
      const calls = stubApi({ orderRefusal: 'incomplete' })
      const user = userEvent.setup()
      await openItems(user)
      const loads = () => sent(calls, 'GET /api/v1/dashboard/catalog/items').length

      const before = loads()
      await user.click(screen.getByRole('button', { name: 'Move WSP Caramel down' }))
      expect(await screen.findByText('The order must list every one of the 3 items exactly once')).toBeInTheDocument()
      await vi.waitFor(() => expect(loads()).toBeGreaterThan(before))
    })

    it('a reorder after saving an item here carries the item’s newer version', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const price = screen.getByLabelText('Price of WSP Caramel')
      await user.clear(price)
      await user.type(price, '17.50')
      await user.click(screen.getByRole('button', { name: 'Save WSP Caramel' }))
      expect(await screen.findByText('Saved')).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: 'Move WSP Caramel down' }))
      await vi.waitFor(() => expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1/items/order')).toHaveLength(1))
      expect(sent(calls, 'PUT /api/v1/dashboard/catalog/folders/1/items/order')[0].body).toEqual({ ids: [8, 7], versions: [4, 4] })
    })

    it('reorders options with the versions they were loaded at', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      await user.click(screen.getByRole('button', { name: 'Options of WSP Caramel' }))
      const editor = await screen.findByRole('group', { name: 'Options of WSP Caramel' })
      await user.click(within(editor).getByRole('button', { name: 'Move option Matcha earlier' }))
      await vi.waitFor(() => expect(sent(calls, 'PUT /api/v1/dashboard/catalog/modifier-groups/5/options/order')[0].body)
        .toEqual({ ids: [12, 11], versions: [10, 9] }))
    })

    it('rebuilds a folder toolbar when its tab is renamed, so saving it cannot put it on the old name', async () => {
      stubApi()
      const user = userEvent.setup()
      await openItems(user)

      const tabs = await screen.findByRole('region', { name: 'Tabs' })
      const tabName = within(tabs).getByLabelText('Name of tab Selling Items')
      await user.clear(tabName)
      await user.type(tabName, 'Main')
      await user.click(within(tabs).getByRole('button', { name: 'Save tab Selling Items' }))

      // The folders on that tab show the new name and are not left looking edited.
      await vi.waitFor(() => expect(screen.getByLabelText('Tab of folder White Stand Pouch')).toHaveValue('Main'))
      expect(screen.getByRole('button', { name: 'Save folder White Stand Pouch' })).toBeDisabled()
      expect(screen.getByLabelText('Tab of folder Empty Folder')).toHaveValue('Main')
    })

    it('reloads when a change is refused as colliding with another one, and not for an unrelated conflict', async () => {
      const collided = stubApi({ folderCollision: true })
      const user = userEvent.setup()
      await openItems(user)
      const loads = (calls: { key: string; body: unknown }[]) => sent(calls, 'GET /api/v1/dashboard/catalog/items').length

      const before = loads(collided)
      const name = screen.getByLabelText('Name of folder White Stand Pouch')
      await user.clear(name)
      await user.type(name, 'Mine')
      await user.click(screen.getByRole('button', { name: 'Save folder White Stand Pouch' }))
      expect(await screen.findByText('That change collided with another one, or with a sale. Reload and try again.')).toBeInTheDocument()
      await vi.waitFor(() => expect(loads(collided)).toBeGreaterThan(before))
      expect(await screen.findByLabelText('Name of folder Pouches (theirs)')).toBeInTheDocument()
    })

    it('an item save refused as colliding with another change reloads the list, and says only what the server said', async () => {
      const calls = stubApi({ itemCollision: true })
      const user = userEvent.setup()
      await openItems(user)
      const loads = () => sent(calls, 'GET /api/v1/dashboard/catalog/items').length

      const before = loads()
      const price = screen.getByLabelText('Price of WSP Caramel')
      await user.clear(price)
      await user.type(price, '17.50')
      await user.click(screen.getByRole('button', { name: 'Save WSP Caramel' }))

      expect(await screen.findByText('That change collided with another one, or with a sale. Reload and try again.')).toBeInTheDocument()
      await vi.waitFor(() => expect(loads()).toBeGreaterThan(before))
      // Not claimed to be someone else's edit of this item, and not shown as saved.
      expect(screen.queryByText(/was changed by someone else/)).not.toBeInTheDocument()
      expect(screen.queryByText('Saved')).not.toBeInTheDocument()
    })

    it('an item save refused for its own name, such as one already in the folder, stays on the row without a reload', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)
      const loads = () => sent(calls, 'GET /api/v1/dashboard/catalog/items').length

      const before = loads()
      const name = screen.getByLabelText('Name of WSP Caramel')
      await user.clear(name)
      await user.type(name, 'Dup')
      await user.click(screen.getByRole('button', { name: 'Save WSP Caramel' }))

      const row = screen.getByLabelText('Price of WSP Caramel').closest('tr')!
      expect(await within(row).findByText('White Stand Pouch already has an item called Dup')).toBeInTheDocument()
      expect(loads()).toBe(before)
      expect(screen.getByLabelText('Name of WSP Caramel')).toHaveValue('Dup')
    })

    it('does not reload for a conflict that is about the name typed, such as a name already taken', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)
      const loads = () => sent(calls, 'GET /api/v1/dashboard/catalog/items').length

      const before = loads()
      const name = screen.getByLabelText('Name of folder White Stand Pouch')
      await user.clear(name)
      await user.type(name, 'Boom')
      await user.click(screen.getByRole('button', { name: 'Save folder White Stand Pouch' }))
      expect(await screen.findByText('A folder named Boom already exists')).toBeInTheDocument()
      expect(loads()).toBe(before)
      // What was typed is still there to correct.
      expect(screen.getByLabelText('Name of folder White Stand Pouch')).toHaveValue('Boom')
    })

    it('manages an item’s option groups and options, keeping sold ones as inactive', async () => {
      const calls = stubApi()
      const user = userEvent.setup()
      await openItems(user)

      await user.click(screen.getByRole('button', { name: 'Options of WSP Caramel' }))
      const editor = await screen.findByRole('group', { name: 'Options of WSP Caramel' })
      expect(within(editor).getByLabelText('Name of option Matcha')).toBeInTheDocument()

      await user.type(within(editor).getByLabelText('New option name for Flavours'), 'Cheese')
      await user.click(within(editor).getByRole('button', { name: 'Add option to Flavours' }))
      await vi.waitFor(() => expect(sent(calls, 'POST /api/v1/dashboard/catalog/modifier-groups/5/options')[0].body)
        .toEqual({ name: 'Cheese', price: 0 }))

      await user.click(await within(await screen.findByRole('group', { name: 'Options of WSP Caramel' })).findByRole('button', { name: 'Remove option Matcha' }))
      await answerConfirm(user, 'Remove the Matcha option?', 'Cancel')
      expect(sent(calls, 'DELETE /api/v1/dashboard/catalog/modifier-options/12')).toHaveLength(0)
      await user.click(within(await screen.findByRole('group', { name: 'Options of WSP Caramel' })).getByRole('button', { name: 'Remove option Matcha' }))
      await answerConfirm(user, 'Remove the Matcha option?', 'Remove option')
      expect(await screen.findByText('Matcha was sold before: kept, but no longer offered')).toBeInTheDocument()
    })
  })

  it('head office makes an item a seasonal flavour until its last day', async () => {
    const calls = stubApi()
    const user = userEvent.setup()
    await logInAsHeadOffice(user)
    await user.click(await nav().findByRole('button', { name: 'Items & Prices' }))

    await user.click(await screen.findByLabelText('WSP Caramel is a seasonal flavour'))
    const row = screen.getByLabelText('Price of WSP Caramel').closest('tr')!
    // A seasonal flavour is for a limited time: no saving without its last day.
    expect(within(row).getByRole('button', { name: 'Save WSP Caramel' })).toBeDisabled()
    await user.type(within(row).getByLabelText('WSP Caramel seasonal until'), '2026-12-31')
    await user.click(within(row).getByRole('button', { name: 'Save WSP Caramel' }))

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
