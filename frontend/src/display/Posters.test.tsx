import { act, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Posters, ROTATE_MS } from './Posters'

const heading = () => screen.getByRole('heading')
const dots = () => document.querySelectorAll('[aria-label="Posters"] > div.gap-2 > span')
const shownTitles = () => screen.getAllByRole('heading').map((h) => h.textContent)

afterEach(() => {
  vi.useRealTimers()
})

describe('posters: which ones show', () => {
  it('shows the posters of the keys the server gives, in its order', () => {
    render(<Posters outletName="Counterline Bukit Bintang" keys={['SEASONAL', 'BUY_THREE']} />)
    expect(heading()).toHaveTextContent('Seasonal flavours are here')
    expect(dots()).toHaveLength(2)
  })

  it('ignores keys it does not know, and repeats', () => {
    render(<Posters outletName="Counterline" keys={['MYSTERY', 'toString', '__proto__', 'BIRTHDAY', 'BIRTHDAY']} />)
    expect(heading()).toHaveTextContent('Birthday month treat')
    expect(dots()).toHaveLength(1)
  })

  it.each([
    ['no answer yet', null],
    ['no promotion running', []],
    ['only keys it does not know', ['SOMETHING_NEW']],
  ])('shows only neutral posters for %s', (_, keys) => {
    vi.useFakeTimers()
    render(<Posters outletName="Counterline" keys={keys} />)
    expect(dots()).toHaveLength(2)
    expect(heading()).toHaveTextContent('Become a member')
    act(() => vi.advanceTimersByTime(ROTATE_MS))
    expect(heading()).toHaveTextContent('Welcome')
    // Nothing a customer could take as a promise of a particular reward.
    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/free|RM\d|points|buy \d|%/i)
  })

  it('does not leave the neutral posters up once the server names a promotion, and goes back when it stops', () => {
    const { rerender } = render(<Posters outletName="Counterline" keys={null} />)
    expect(heading()).toHaveTextContent('Become a member')
    rerender(<Posters outletName="Counterline" keys={['SPEND_ANYONE']} />)
    expect(heading()).toHaveTextContent('Free gift with your spend')
    rerender(<Posters outletName="Counterline" keys={null} />)
    expect(heading()).toHaveTextContent('Become a member')
  })
})

describe('posters: what each one says', () => {
  const all = ['BUY_THREE', 'SEASONAL', 'MEMBER_POINTS', 'BIRTHDAY', 'SPEND_MEMBERS', 'SPEND_ANYONE']

  it('says "Terms apply" on every promotion poster', () => {
    vi.useFakeTimers()
    render(<Posters outletName="Counterline" keys={all} />)
    for (let i = 0; i < all.length; i++) {
      expect(screen.getByRole('heading').parentElement).toHaveTextContent('Terms apply.')
      act(() => vi.advanceTimersByTime(ROTATE_MS))
    }
  })

  it.each([
    ['BUY_THREE', /Members get a free item for every 3 qualifying items\./, /Not with vouchers or discounts/],
    ['SEASONAL', /Members get one more free item for every 3 items with a seasonal flavour among them/, /Not with vouchers or discounts/],
    ['BIRTHDAY', /Members get a free birthday reward with every 3 qualifying items in their birthday month, up to a limit per receipt/, /Not with vouchers or discounts/],
    ['SPEND_MEMBERS', /Members get a free gift/, /Ask the cashier/],
    ['SPEND_ANYONE', /subject to limits/, /Ask the cashier/],
    ['MEMBER_POINTS', /Members earn points on every purchase/, /Ask the cashier to join/],
  ])('%s states who gets it and its limits', (key, first, second) => {
    render(<Posters outletName="Counterline" keys={[key]} />)
    const text = screen.getByRole('heading').parentElement!.textContent
    expect(text).toMatch(first)
    expect(text).toMatch(second)
  })
})

describe('posters: rotation', () => {
  it('shows each poster for 10 seconds, then slides the next one in', () => {
    vi.useFakeTimers()
    render(<Posters outletName="Counterline Bukit Bintang" keys={['BUY_THREE', 'SEASONAL', 'BIRTHDAY']} />)
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

  it('keeps its colours: one gradient per tone', () => {
    render(<Posters outletName="Counterline" keys={['BUY_THREE', 'SEASONAL', 'MEMBER_POINTS']} />)
    expect(heading().parentElement).toHaveClass('from-indigo-600', 'via-violet-600', 'to-fuchsia-600')
  })

  it('does not rotate a single poster', () => {
    vi.useFakeTimers()
    render(<Posters outletName="Counterline" keys={['SPEND_MEMBERS']} />)
    expect(vi.getTimerCount()).toBe(0)
    act(() => vi.advanceTimersByTime(ROTATE_MS * 5))
    expect(shownTitles()).toEqual(['Free gift for members'])
    expect(heading().parentElement).not.toHaveClass('animate-slide-in-right')
    expect(dots()).toHaveLength(1)
  })

  it('starts over from the first poster when the list shrinks, so the index is always valid', () => {
    vi.useFakeTimers()
    const { rerender } = render(<Posters outletName="Counterline" keys={['BUY_THREE', 'SEASONAL', 'BIRTHDAY']} />)
    act(() => vi.advanceTimersByTime(ROTATE_MS * 2))
    expect(heading()).toHaveTextContent('Birthday month treat')
    expect(dots()).toHaveLength(3)

    rerender(<Posters outletName="Counterline" keys={['BUY_THREE']} />)
    expect(shownTitles()).toEqual(['Buy 3, get 1 free'])
    expect(dots()).toHaveLength(1)
    act(() => vi.advanceTimersByTime(ROTATE_MS * 3))
    expect(shownTitles()).toEqual(['Buy 3, get 1 free'])

    rerender(<Posters outletName="Counterline" keys={['BUY_THREE', 'MEMBER_POINTS']} />)
    expect(dots()).toHaveLength(2)
    expect(heading()).toHaveTextContent('Buy 3, get 1 free')
  })

  it('keeps going when the same keys come again, and stops its timer when it goes', () => {
    vi.useFakeTimers()
    const { rerender, unmount } = render(<Posters outletName="Counterline" keys={['BUY_THREE', 'SEASONAL']} />)
    act(() => vi.advanceTimersByTime(ROTATE_MS))
    rerender(<Posters outletName="Counterline" keys={['BUY_THREE', 'SEASONAL']} />)
    expect(heading()).toHaveTextContent('Seasonal flavours are here')
    expect(vi.getTimerCount()).toBe(1)
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
