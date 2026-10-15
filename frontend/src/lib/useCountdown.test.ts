import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useCountdown } from './useCountdown'

describe('useCountdown', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('counts down each second and calls back once at zero', () => {
    vi.useFakeTimers()
    const onElapsed = vi.fn()
    const { result } = renderHook(() => useCountdown(5, true, onElapsed))
    expect(result.current).toBe(5)

    act(() => vi.advanceTimersByTime(1000))
    expect(result.current).toBe(4)
    act(() => vi.advanceTimersByTime(3900))
    expect(onElapsed).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(100))
    expect(onElapsed).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(5000))
    expect(onElapsed).toHaveBeenCalledTimes(1)
  })

  it('waits while not running, and starts again in full when the restart key changes', () => {
    vi.useFakeTimers()
    const onElapsed = vi.fn()
    const { result, rerender } = renderHook(({ running, restartKey }) => useCountdown(5, running, onElapsed, restartKey), {
      initialProps: { running: false, restartKey: 0 },
    })
    act(() => vi.advanceTimersByTime(10_000))
    expect(onElapsed).not.toHaveBeenCalled()

    rerender({ running: true, restartKey: 0 })
    act(() => vi.advanceTimersByTime(3000))
    expect(result.current).toBe(2)
    rerender({ running: true, restartKey: 1 })
    expect(result.current).toBe(5)
    act(() => vi.advanceTimersByTime(4900))
    expect(onElapsed).not.toHaveBeenCalled()
    act(() => vi.advanceTimersByTime(100))
    expect(onElapsed).toHaveBeenCalledTimes(1)
  })
})
