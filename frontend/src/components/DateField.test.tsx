import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { DateField } from './DateField'

function Harness({ initial = '', onChange }: { initial?: string; onChange: (value: string) => void }) {
  const [value, setValue] = useState(initial)
  return (
    <>
      <DateField
        value={value}
        onChange={(next) => {
          setValue(next)
          onChange(next)
        }}
      />
      <p>Elsewhere</p>
    </>
  )
}

const picked = (name: string) =>
  screen.getByRole('listbox', { name }).querySelector('[aria-selected="true"]')?.textContent

describe('DateField', () => {
  it('looks like an empty field, and drops down the wheels when tapped', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    const field = screen.getByRole('button', { name: 'Date of birth: not set' })
    expect(field).toHaveTextContent('DD / MM / YYYY')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    await user.click(field)
    expect(field).toHaveAttribute('aria-expanded', 'true')
    await user.click(screen.getByRole('option', { name: '15' }))
    await user.click(screen.getByRole('option', { name: 'Mar' }))
    await user.click(screen.getByRole('option', { name: '1995' }))
    expect(onChange).toHaveBeenLastCalledWith('1995-03-15')
    await user.click(screen.getByRole('button', { name: 'Done' }))

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Date of birth: 15 Mar 1995' })).toHaveTextContent('15 Mar 1995')
  })

  it('keeps the day inside the month, closes when tapped elsewhere, and clears', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<Harness initial="2001-03-31" onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Date of birth: 31 Mar 2001' }))
    expect(picked('Day')).toBe('31')
    await user.click(screen.getByRole('option', { name: 'Feb' }))
    expect(onChange).toHaveBeenLastCalledWith('2001-02-28')
    expect(picked('Day')).toBe('28')

    screen.getByRole('listbox', { name: 'Year' }).focus()
    await user.keyboard('{ArrowDown}')
    expect(onChange).toHaveBeenLastCalledWith('2000-02-28')

    await user.click(screen.getByText('Elsewhere'))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Date of birth: 28 Feb 2000' }))
    await user.click(screen.getByRole('button', { name: 'Clear' }))
    expect(onChange).toHaveBeenLastCalledWith('')
    expect(screen.getByRole('button', { name: 'Date of birth: not set' })).toBeInTheDocument()
  })
})
