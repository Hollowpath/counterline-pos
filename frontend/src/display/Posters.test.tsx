import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Posters, ROTATE_MS } from './Posters'

const heading = () => screen.getByRole('heading')

describe('posters', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows each poster for 10 seconds, then slides the next one in', () => {
    vi.useFakeTimers()
    render(<Posters outletName="Counterline Bukit Bintang" />)
    expect(ROTATE_MS).toBe(10_000)
    expect(heading()).toHaveTextContent('Buy 3, get 1 free')

    act(() => vi.advanceTimersByTime(ROTATE_MS - 100))
    expect(heading()).toHaveTextContent('Buy 3, get 1 free')

    act(() => vi.advanceTimersByTime(100))
    // The one leaving is hidden from screen readers while it slides out.
    expect(heading()).toHaveTextContent('Seasonal flavours are here')
    expect(heading().parentElement).toHaveClass('animate-slide-in-right')
    expect(screen.getByRole('heading', { name: 'Buy 3, get 1 free', hidden: true }).parentElement)
      .toHaveClass('animate-slide-out-left')
  })
})
