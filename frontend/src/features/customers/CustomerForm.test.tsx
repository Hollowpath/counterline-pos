import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { CustomerForm } from './CustomerForm'
import type { Customer } from './types'

const FUTURE = "Date of birth can't be in the future"

function customer(dateOfBirth: string | null): Customer {
  return {
    id: 1, version: 0, referenceId: null, firstName: 'Nur', lastName: 'Aisyah', fullName: 'Nur Aisyah', companyName: null,
    dateOfBirth, gender: null, email: null, phone: '+60112345678', marketingEmail: false, marketingSms: false,
    marketingCall: false, streetAddress: null, aptSuite: null, city: null, postalCode: null, country: null, notes: null,
    createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-01T10:00:00Z',
  }
}

function stubApi() {
  const puts: Record<string, unknown>[] = []
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init: RequestInit = {}) => {
    if (init.method === 'PUT') {
      const body = JSON.parse(String(init.body))
      puts.push(body)
      return new Response(JSON.stringify({ ...customer(body.dateOfBirth), version: 1 }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    return new Response('{}', { status: 404 })
  }))
  return puts
}

// The terminal's local calendar date is 30 October 2026, whatever the machine's own date.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], now: new Date(2026, 9, 30, 9, 0, 0) })
})
afterEach(() => {
  vi.useRealTimers()
})

function renderForm(dateOfBirth: string | null) {
  render(<CustomerForm customer={customer(dateOfBirth)} onSaved={vi.fn()} onCancel={vi.fn()} />)
  return { save: () => screen.getByRole('button', { name: 'Save' }) }
}

describe('CustomerForm: the birth date', () => {
  it('takes today’s date and saves it', async () => {
    const puts = stubApi()
    const user = userEvent.setup()
    const { save } = renderForm('2026-10-30')

    expect(screen.queryByText(FUTURE)).not.toBeInTheDocument()
    expect(save()).toBeEnabled()
    await user.click(save())

    await vi.waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0]).toMatchObject({ dateOfBirth: '2026-10-30', version: 0 })
  })

  it('refuses tomorrow’s date at the field, and sends nothing', async () => {
    const puts = stubApi()
    const user = userEvent.setup()
    const { save } = renderForm('2026-10-31')

    expect(screen.getByRole('alert')).toHaveTextContent(FUTURE)
    expect(save()).toBeDisabled()
    await user.click(save())
    expect(puts).toHaveLength(0)
  })

  it('takes yesterday’s date and one in the past', async () => {
    stubApi()
    const { save } = renderForm('2026-10-29')
    expect(screen.queryByText(FUTURE)).not.toBeInTheDocument()
    expect(save()).toBeEnabled()
  })

  it('says nothing more once the date is cleared', async () => {
    const puts = stubApi()
    const user = userEvent.setup()
    const { save } = renderForm('2026-10-31')
    expect(screen.getByRole('alert')).toHaveTextContent(FUTURE)

    await user.click(screen.getByRole('button', { name: /Date of birth: 31 Oct 2026/ }))
    await user.click(within(screen.getByRole('dialog', { name: 'Date of birth' })).getByRole('button', { name: 'Clear' }))
    expect(screen.queryByText(FUTURE)).not.toBeInTheDocument()
    expect(save()).toBeEnabled()
    await user.click(save())
    await vi.waitFor(() => expect(puts).toHaveLength(1))
    expect(puts[0].dateOfBirth).toBeNull()
  })

  it('does not offer a year after this one on the picker', async () => {
    stubApi()
    const user = userEvent.setup()
    renderForm(null)

    await user.click(screen.getByRole('button', { name: 'Date of birth: not set' }))
    const years = within(screen.getByRole('listbox', { name: 'Year' })).getAllByRole('option').map((option) => option.textContent)
    expect(years[0]).toBe('2026')
  })
})
