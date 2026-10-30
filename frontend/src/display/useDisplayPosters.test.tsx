import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { POSTERS_MAX_AGE_MS, POSTERS_REFRESH_EARLY_MS, POSTERS_RETRY_MS, useDisplayPosters } from './useDisplayPosters'

const T0 = Date.parse('2026-03-10T04:00:00Z')

interface Server {
  /** What the next ask gets: a body, or 'fail'. */
  next: unknown
  calls: AbortSignal[]
}

function stubServer(): Server {
  const server: Server = { next: 'fail', calls: [] }
  vi.stubGlobal('fetch', vi.fn(async (_input: RequestInfo | URL, init: RequestInit = {}) => {
    server.calls.push(init.signal as AbortSignal)
    if (server.next === 'fail') return new Response('{}', { status: 500 })
    return new Response(JSON.stringify(server.next), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
  return server
}

const answer = (posters: string[], afterMs: number) => ({ posters, validUntil: new Date(T0 + afterMs).toISOString() })
const settle = (ms = 0) => act(async () => { await vi.advanceTimersByTimeAsync(ms) })

beforeEach(() => {
  vi.useFakeTimers({ now: T0 })
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useDisplayPosters', () => {
  it('has no answer before the first one arrives, then the server keys', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE', 'BIRTHDAY'], 60_000)
    const { result } = renderHook(() => useDisplayPosters(true))
    expect(result.current).toBeNull()
    await settle()
    expect(result.current).toEqual(['BUY_THREE', 'BIRTHDAY'])
    expect(server.calls).toHaveLength(1)
  })

  it('asks only while idle, and again each time it returns to idle', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 120_000)
    const { rerender } = renderHook(({ idle }) => useDisplayPosters(idle), { initialProps: { idle: false } })
    await settle(1000)
    expect(server.calls).toHaveLength(0)

    rerender({ idle: true })
    await settle()
    expect(server.calls).toHaveLength(1)

    rerender({ idle: false })
    await settle(30_000)
    expect(server.calls).toHaveLength(1)
    rerender({ idle: true })
    await settle()
    expect(server.calls).toHaveLength(2)
  })

  it('asks again shortly before the answer expires, not before', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 60_000)
    renderHook(() => useDisplayPosters(true))
    await settle()
    await settle(60_000 - POSTERS_REFRESH_EARLY_MS - 1)
    expect(server.calls).toHaveLength(1)
    server.next = answer(['SPEND_MEMBERS'], 360_000)
    await settle(1)
    expect(server.calls).toHaveLength(2)
  })

  it('asks again as a short answer ends, such as one near the outlet’s midnight', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 3_000)
    renderHook(() => useDisplayPosters(true))
    await settle()
    await settle(2_999)
    expect(server.calls).toHaveLength(1)
    await settle(1)
    expect(server.calls).toHaveLength(2)
  })

  it('swaps in the new answer when it changes', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 60_000)
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle()
    server.next = answer(['BUY_THREE', 'SEASONAL'], 120_000)
    await settle(60_000 - POSTERS_REFRESH_EARLY_MS)
    expect(result.current).toEqual(['BUY_THREE', 'SEASONAL'])
  })

  it('keeps the last good answer when asking fails, but only until its expiry', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 90_000)
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle()

    server.next = 'fail'
    await settle(90_000 - POSTERS_REFRESH_EARLY_MS)
    expect(server.calls).toHaveLength(2)
    expect(result.current).toEqual(['BUY_THREE'])

    // The answer goes at its own expiry, not later, and the display keeps trying.
    await settle(POSTERS_REFRESH_EARLY_MS - 1)
    expect(result.current).toEqual(['BUY_THREE'])
    await settle(1)
    expect(result.current).toBeNull()
    const asked = server.calls.length
    await settle(POSTERS_RETRY_MS)
    expect(server.calls.length).toBeGreaterThan(asked)
    expect(result.current).toBeNull()
  })

  it('recovers when the server comes back', async () => {
    const server = stubServer()
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle()
    expect(result.current).toBeNull()
    server.next = answer(['SPEND_ANYONE'], 300_000)
    await settle(POSTERS_RETRY_MS)
    expect(result.current).toEqual(['SPEND_ANYONE'])
  })

  it('never shows an answer for longer than five minutes, whatever its expiry says', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 24 * 3_600_000)
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle()
    server.next = 'fail'
    await settle(POSTERS_MAX_AGE_MS - 1)
    expect(result.current).toEqual(['BUY_THREE'])
    await settle(1)
    expect(result.current).toBeNull()
  })

  it('does not use an answer that has already expired', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], -1000)
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle(1)
    expect(result.current).toBeNull()
    // It asks again soon, but not in a tight loop.
    await settle(4000)
    expect(server.calls).toHaveLength(1)
    await settle(1000)
    expect(server.calls).toHaveLength(2)
  })

  it('drops an expired answer while the display is busy, so it is not shown on return', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 60_000)
    const { result, rerender } = renderHook(({ idle }) => useDisplayPosters(idle), { initialProps: { idle: true } })
    await settle()
    rerender({ idle: false })
    await settle(61_000)
    expect(result.current).toBeNull()
  })

  it.each([
    ['a body that is not an answer', { nonsense: true }],
    ['posters that are not a list', { posters: 'BUY_THREE', validUntil: new Date(T0 + 60_000).toISOString() }],
    ['an expiry that is not a date', { posters: ['BUY_THREE'], validUntil: 'soon' }],
  ])('treats %s as no answer', async (_, body) => {
    const server = stubServer()
    server.next = body
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle()
    expect(result.current).toBeNull()
  })

  it('keeps only text keys, leaving the unknown ones to the posters', async () => {
    const server = stubServer()
    server.next = { posters: ['BUY_THREE', 7, null, 'NEW_ONE'], validUntil: new Date(T0 + 60_000).toISOString() }
    const { result } = renderHook(() => useDisplayPosters(true))
    await settle()
    expect(result.current).toEqual(['BUY_THREE', 'NEW_ONE'])
  })

  it('cancels its request and timers when it goes', async () => {
    const server = stubServer()
    server.next = answer(['BUY_THREE'], 60_000)
    const { unmount } = renderHook(() => useDisplayPosters(true))
    await settle()
    unmount()
    expect(server.calls[0].aborted).toBe(true)
    expect(vi.getTimerCount()).toBe(0)
    await settle(120_000)
    expect(server.calls).toHaveLength(1)
  })
})
