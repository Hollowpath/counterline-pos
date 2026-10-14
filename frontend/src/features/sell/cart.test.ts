import { describe, expect, it } from 'vitest'
import {
  cartItemCount,
  cartReducer,
  cartSubtotalSen,
  cartTotalSen,
  emptyCart,
  lineTotalSen,
  orderDiscountAmountsSen,
  sortedLines,
  type CartLine,
} from './cart'
import type { DiscountRule } from './pricing'

const caramel = { id: 1, name: 'Pouch Caramel', price: 14.5, imageUrl: null }
const tin = { id: 2, name: 'Tin Cheese', price: 29.1, imageUrl: null }
const staff30: DiscountRule = { id: 1, name: 'Staff', kind: 'PERCENT', value: 30 }
const voucher10: DiscountRule = { id: 2, name: 'Voucher', kind: 'AMOUNT', value: 10 }
const order10: DiscountRule = { id: 3, name: 'Order 10%', kind: 'PERCENT', value: 10 }

describe('the order lines are listed in', () => {
  it('charged items counting towards Buy 3 first, other items next, free items last, each as added', () => {
    const b3f1 = { id: 1, name: 'Buy 3 Free 1 Promo', type: 'BUY_X_GET_FREE' as const, pointsCost: 0, membersOnly: true }
    let cart = emptyCart
    for (const item of [
      { id: 10, name: 'PC Cheese', price: 10, imageUrl: null, countsForPromotions: true },
      { id: 10, name: 'PC Cheese', price: 10, imageUrl: null, countsForPromotions: true },
      { id: 21, name: 'B3 F1 BC BBQ', price: 6.5, imageUrl: null, promotion: b3f1 },
      { id: 30, name: 'Recycle Bag (S)', price: 0.5, imageUrl: null, countsForPromotions: false },
      { id: 11, name: 'PC Caramel', price: 10, imageUrl: null, countsForPromotions: true },
      { id: 40, name: 'SPL Cheese', price: 0, imageUrl: null, countsForPromotions: false },
    ]) {
      cart = cartReducer(cart, { type: 'addItem', item })
    }
    cart = cartReducer(cart, { type: 'addCustomAmount', amountSen: 500 })

    expect(sortedLines(cart.lines).map((line) => `${line.quantity} ${line.name}`)).toEqual([
      '2 PC Cheese', '1 PC Caramel', '1 Recycle Bag (S)', '1 Custom Amount', '1 B3 F1 BC BBQ', '1 SPL Cheese',
    ])
  })
})

describe('cartReducer', () => {
  it('loads a placed order for editing, with its discounts, voucher and customer', () => {
    const cart = cartReducer(emptyCart, {
      type: 'loadOrder',
      order: {
        id: 9, orderNumber: 'R9', note: 'Collect at 5pm',
        customer: { id: 5, name: 'Nur Aisyah', phone: '+60112345678', email: null },
        lines: [{
          itemId: 1, name: 'Pouch Caramel', unitPrice: 14.5, quantity: 2, note: null, modifiers: [],
          discounts: [], promotion: null, countsForPromotions: true,
        }],
        orderDiscounts: [{
          id: 7, name: 'RM10 Voucher', kind: 'AMOUNT', value: 10, requiresApproval: false, notForMembers: false, countsAsVoucher: true,
          voucherCode: 'ABCD2345EF',
        }],
      },
    })

    expect(cart.editing).toEqual({ orderId: 9, orderNumber: 'R9' })
    expect(cart.customer).toEqual({ id: 5, name: 'Nur Aisyah', phone: '+60112345678' })
    expect(cart.lines[0]).toMatchObject({ itemId: 1, unitPriceSen: 1450, quantity: 2, note: '', countsForPromotions: true })
    expect(cart.orderDiscounts[0]).toMatchObject({ id: 7, voucherCode: 'ABCD2345EF' })
    expect(cartTotalSen(cart)).toBe(1900)
    expect(cartReducer(cart, { type: 'clear' }).editing).toBeNull()
  })

  it('adds a new line, then increments quantity when the same plain item is tapped again', () => {
    let cart = cartReducer(emptyCart, { type: 'addItem', item: caramel })
    cart = cartReducer(cart, { type: 'addItem', item: caramel })

    expect(cart.lines).toHaveLength(1)
    expect(cart.lines[0]).toMatchObject({ itemId: 1, name: 'Pouch Caramel', unitPriceSen: 1450, quantity: 2 })
  })

  it('keeps a line with a discount or note separate from plain taps of the same item', () => {
    let cart = cartReducer(emptyCart, { type: 'addItem', item: caramel })
    const [plain] = cart.lines
    cart = cartReducer(cart, { type: 'updateLine', key: plain.key, line: { ...plain, discounts: [staff30] } })
    cart = cartReducer(cart, { type: 'addItem', item: caramel })

    expect(cart.lines.map((line) => line.discounts.length)).toEqual([1, 0])
  })

  it('totals in integer sen without floating-point drift', () => {
    let cart = emptyCart
    for (let i = 0; i < 3; i++) cart = cartReducer(cart, { type: 'addItem', item: tin })
    cart = cartReducer(cart, { type: 'addItem', item: caramel })

    // 3 × 29.10 + 14.50 = 101.80 (float arithmetic would give 101.80000000000001)
    expect(cartSubtotalSen(cart)).toBe(10180)
    expect(cartItemCount(cart)).toBe(4)
  })

  it('removes a line by key and clears the whole sale', () => {
    let cart = cartReducer(emptyCart, { type: 'addItem', item: caramel })
    cart = cartReducer(cart, { type: 'addItem', item: tin })
    cart = cartReducer(cart, { type: 'setNote', note: 'Gift wrap' })

    cart = cartReducer(cart, { type: 'removeLine', key: cart.lines[0].key })
    expect(cart.lines.map((line) => line.name)).toEqual(['Tin Cheese'])

    expect(cartReducer(cart, { type: 'clear' })).toEqual(emptyCart)
  })
})

describe('amounts (mirror the server)', () => {
  /** The server's worked example: see OptionsAndDiscountsIntegrationTest. */
  it('prices options, line discounts and order discounts like the server', () => {
    const giftBox: Omit<CartLine, 'key'> = {
      itemId: 7, name: 'Gift Box', unitPriceSen: 7000, quantity: 2, note: '',
      modifiers: [
        { optionId: 1, name: 'Caramel', quantity: 1, priceSen: 0 },
        { optionId: 3, name: 'Matcha', quantity: 2, priceSen: 200 },
      ],
      discounts: [staff30, voucher10],
    }
    let cart = cartReducer(emptyCart, { type: 'addLine', line: giftBox })
    cart = cartReducer(cart, { type: 'addItem', item: { id: 1, name: 'Pouch Cheese', price: 16, imageUrl: null } })
    cart = cartReducer(cart, { type: 'setOrderDiscounts', discounts: [order10] })

    expect(lineTotalSen(cart.lines[0])).toBe(9360) // 148.00 − 44.40 − 10.00
    expect(cartSubtotalSen(cart)).toBe(16400)
    expect(orderDiscountAmountsSen(cart)).toEqual([1096]) // 10% of 109.60
    expect(cartTotalSen(cart)).toBe(9864)
  })

  it('rounds percentages half-up and never discounts below zero', () => {
    const line = { itemId: 1, name: 'x', unitPriceSen: 1650, quantity: 1, note: '', modifiers: [] }
    expect(lineTotalSen({ ...line, discounts: [{ id: 9, name: '15%', kind: 'PERCENT', value: 15 }] })).toBe(1650 - 248)
    expect(lineTotalSen({ ...line, discounts: [staff30, { id: 4, name: 'Comp', kind: 'PERCENT', value: 100 }] })).toBe(0)
  })

  it('adds custom amounts as their own lines', () => {
    const cart = cartReducer(emptyCart, { type: 'addCustomAmount', amountSen: 1250 })
    expect(cart.lines[0]).toMatchObject({ itemId: null, name: 'Custom Amount', unitPriceSen: 1250, quantity: 1 })
  })
})
