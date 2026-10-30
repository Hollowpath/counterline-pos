import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerDisplaysPanel } from './CustomerDisplaysPanel'
import type { CustomerDisplayRow } from './types'

const connected: CustomerDisplayRow = {
  id: 7, outletId: 1, outletName: 'Counterline Bukit Bintang', connected: true,
  createdAt: '2026-10-12T02:00:00Z', connectedAt: '2026-10-12T02:01:00Z', lastSeenAt: '2026-10-12T09:00:00Z',
}
const waiting: CustomerDisplayRow = {
  id: 8, outletId: 2, outletName: 'Counterline Cheras', connected: false,
  createdAt: '2026-10-12T09:00:00Z', connectedAt: null, lastSeenAt: null,
}

function stubApi(rows: CustomerDisplayRow[], disconnectStatus = 204) {
  const calls: { method: string; path: string }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = new URL(String(input), 'http://localhost').pathname
    const method = init.method ?? 'GET'
    calls.push({ method, path })
    if (method === 'POST') {
      return disconnectStatus === 204
        ? new Response(null, { status: 204 })
        : new Response(JSON.stringify({ detail: 'Customer display 7 not found' }), { status: 404, headers: { 'Content-Type': 'application/problem+json' } })
    }
    return new Response(JSON.stringify(rows), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return calls
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerDisplaysPanel', () => {
  it('lists connected and waiting displays with their outlet', async () => {
    stubApi([connected, waiting])
    render(<CustomerDisplaysPanel />)

    const panel = await screen.findByRole('region', { name: 'Customer displays' })
    const rows = within(panel).getAllByRole('row').slice(1)
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Counterline Bukit Bintang')
    expect(rows[0]).toHaveTextContent('Connected')
    expect(rows[1]).toHaveTextContent('Counterline Cheras')
    expect(rows[1]).toHaveTextContent('Waiting for its code')
  })

  it('says so when there are none', async () => {
    stubApi([])
    render(<CustomerDisplaysPanel />)
    expect(await screen.findByText('No customer displays are connected.')).toBeInTheDocument()
  })

  it('disconnects a display after a confirmation, then lists again', async () => {
    const calls = stubApi([connected])
    const user = userEvent.setup()
    render(<CustomerDisplaysPanel />)

    await user.click(await screen.findByRole('button', { name: 'Disconnect the customer display at Counterline Bukit Bintang' }))
    expect(calls.filter((call) => call.method === 'POST')).toHaveLength(0)
    await user.click(screen.getByRole('button', { name: 'Confirm disconnect' }))

    expect(await screen.findByRole('status')).toHaveTextContent('The customer display at Counterline Bukit Bintang was disconnected.')
    expect(calls).toContainEqual({ method: 'POST', path: '/api/v1/dashboard/displays/7/disconnect' })
    await waitFor(() => expect(calls.filter((call) => call.method === 'GET')).toHaveLength(2))
  })

  it('keeps the display when the confirmation is cancelled, and says why a disconnect failed', async () => {
    stubApi([connected], 404)
    const user = userEvent.setup()
    render(<CustomerDisplaysPanel />)

    await user.click(await screen.findByRole('button', { name: 'Disconnect the customer display at Counterline Bukit Bintang' }))
    await user.click(screen.getByRole('button', { name: 'Keep' }))
    expect(screen.getByRole('button', { name: 'Disconnect the customer display at Counterline Bukit Bintang' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Disconnect the customer display at Counterline Bukit Bintang' }))
    await user.click(screen.getByRole('button', { name: 'Confirm disconnect' }))
    expect(await screen.findByRole('status')).toHaveTextContent('Customer display 7 not found')
  })
})
