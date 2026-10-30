import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { CustomerPicker } from './CustomerPicker'

function stubSearch() {
  const asked: URL[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://pos.test')
    asked.push(url)
    return new Response(JSON.stringify({
      items: [{ id: 5, fullName: 'Nur Aisyah', phone: '+60112345678', email: null, referenceId: null }],
      page: 0, size: 20, totalItems: 1, totalPages: 1,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return asked
}

afterEach(() => vi.unstubAllGlobals())

describe('CustomerPicker', () => {
  it('asks for nothing until there is enough to find someone by, and says how much', async () => {
    const asked = stubSearch()
    const user = userEvent.setup()
    render(<CustomerPicker onSelect={() => {}} onCancel={() => {}} />)

    expect(screen.getByText('Type at least 3 letters of a name, or 4 digits of a phone number.')).toBeInTheDocument()
    await user.type(screen.getByRole('searchbox', { name: 'Search customers' }), 'nu')
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(asked).toHaveLength(0)
    expect(screen.getByText(/Type at least 3 letters/)).toBeInTheDocument()

    await user.type(screen.getByRole('searchbox', { name: 'Search customers' }), 'r')
    expect(await screen.findByRole('button', { name: /Nur Aisyah/ })).toBeInTheDocument()
    expect(Object.fromEntries(asked.at(-1)!.searchParams)).toMatchObject({ field: 'NAME', q: 'nur' })
  })

  it('searches a phone number from its fourth digit', async () => {
    const asked = stubSearch()
    const user = userEvent.setup()
    render(<CustomerPicker onSelect={() => {}} onCancel={() => {}} />)

    const box = screen.getByRole('searchbox', { name: 'Search customers' })
    await user.type(box, '011')
    await new Promise((resolve) => setTimeout(resolve, 450))
    expect(asked).toHaveLength(0)

    await user.type(box, '2')
    await waitFor(() => expect(asked).toHaveLength(1))
    expect(Object.fromEntries(asked[0].searchParams)).toMatchObject({ field: 'PHONE', q: '0112' })
  })
})
