import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MemberRewardsPanel } from './MemberRewardsPanel'

function stubPoints(balance: number) {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = new URL(String(input), 'http://localhost').pathname
    const body = path.endsWith('/points')
      ? { balance, entries: [{ type: 'REVERSE_EARN', points: -6, at: '2026-10-12T04:00:00Z', orderNumber: 'R12', reason: null, adjustedBy: null }] }
      : []
    return new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
}

afterEach(() => vi.unstubAllGlobals())

describe('MemberRewardsPanel', () => {
  it('shows a member in debt from a refund clearly, with what happens next', async () => {
    stubPoints(-6)
    render(<MemberRewardsPanel customerId={5} />)

    expect(await screen.findByTestId('points-balance')).toHaveTextContent('-6 points')
    expect(screen.getByTestId('points-balance')).toHaveClass('from-red-600')
    expect(screen.getByTestId('points-owed')).toHaveTextContent('Owes 6 points')
    expect(screen.getByTestId('points-owed')).toHaveTextContent('New purchases earn them back first')
    expect(screen.getByText(/Refund: earned points taken back/)).toBeInTheDocument()
  })

  it('shows nothing of the kind for a balance that is not negative', async () => {
    stubPoints(3)
    render(<MemberRewardsPanel customerId={5} />)

    expect(await screen.findByTestId('points-balance')).toHaveTextContent('3 points')
    expect(screen.getByTestId('points-balance')).toHaveClass('from-indigo-600')
    expect(screen.queryByTestId('points-owed')).not.toBeInTheDocument()
  })
})
