import { useEffect, useState } from 'react'
import { getJson } from '../../api/http'
import { checkoutPaths, type MemberPerks } from '../checkout/types'

/**
 * The member's points, birthday month and new-member gift, fetched as soon as they are added to
 * the sale (null without a member, while loading, or if it could not be fetched: the cart then
 * simply shows less, and checkout still applies every rule).
 */
export function useMemberPerks(customerId: number | null): MemberPerks | null {
  const [state, setState] = useState<{ customerId: number; perks: MemberPerks } | null>(null)

  useEffect(() => {
    if (customerId === null) return
    const controller = new AbortController()
    getJson<MemberPerks>(checkoutPaths.memberPerks(customerId), controller.signal)
      .then((perks) => setState({ customerId, perks }))
      .catch(() => {
        // Optional information; nothing to tell the cashier.
      })
    return () => controller.abort()
  }, [customerId])

  return state !== null && state.customerId === customerId ? state.perks : null
}
