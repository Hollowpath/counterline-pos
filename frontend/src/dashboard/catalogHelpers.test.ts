import { describe, expect, it } from 'vitest'
import { ApiError } from '../api/http'
import {
  COLLIDED, CHANGED_ELSEWHERE, StaleListError, isCollision, message, needsReload, orderBody,
} from './catalogHelpers'

describe('the two answers that mean reload are pinned to the exact text of the server', () => {
  // The same literals are asserted on the server side (ConcurrentChangeMessageContractTest, CatalogCollisionMessageContractTest).
  it('matches what the API sends, word for word', () => {
    expect(CHANGED_ELSEWHERE).toBe('Changed elsewhere. Reload.')
    expect(COLLIDED).toBe('That change collided with another one, or with a sale. Reload and try again.')
  })
})

describe('orderBody', () => {
  const rows = [{ id: 7, version: 3 }, { id: 8, version: 0 }, { id: 9, version: 5 }]

  it('pairs each id with its own row version, in the order wanted', () => {
    expect(orderBody(rows, [9, 7, 8])).toEqual({ ids: [9, 7, 8], versions: [5, 3, 0] })
  })

  it('refuses locally an id that has no version, rather than sending a guess that could pass the check', () => {
    expect(() => orderBody(rows, [7, 10])).toThrow(StaleListError)
    expect(() => orderBody([], [7])).toThrow(StaleListError)
    // Not even for a row whose real version is 0.
    expect(() => orderBody([{ id: 8, version: 0 }], [8, 11])).toThrow('The list is out of date. Reload.')
  })

  it('says what is wrong in the message shown, not "Offline"', () => {
    expect(message(new StaleListError('The list is out of date. Reload.'))).toBe('The list is out of date. Reload.')
    expect(message(new Error('boom'))).toBe('Offline')
  })
})

describe('which refusals mean the screen must reload', () => {
  const api = (status: number, text: string) => new ApiError(status, text)

  it('reloads for a newer version, a collision with another change, an incomplete list, or a local stale list', () => {
    expect(needsReload(api(409, CHANGED_ELSEWHERE))).toBe(true)
    expect(needsReload(api(409, COLLIDED))).toBe(true)
    expect(needsReload(api(400, 'The order must list every one of the 3 items exactly once'))).toBe(true)
    expect(needsReload(new StaleListError('The list is out of date. Reload.'))).toBe(true)
  })

  it('does not reload for other conflicts or errors, even ones that mention reloading', () => {
    expect(needsReload(api(409, 'A folder called Boom already exists'))).toBe(false)
    expect(needsReload(api(409, 'Busy. Retry.'))).toBe(false)
    expect(needsReload(api(409, 'Something else. Reload and try again.'))).toBe(false)
    expect(isCollision(api(500, COLLIDED))).toBe(false)
    expect(needsReload(api(400, 'List the version of every one of the items with the order'))).toBe(false)
    expect(needsReload(new Error('Offline'))).toBe(false)
  })
})
