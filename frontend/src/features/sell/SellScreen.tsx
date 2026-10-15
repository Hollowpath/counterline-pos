import { useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { ApiError } from '../../api/http'
import { useApi } from '../../api/useApi'
import { AppBar } from '../../app/AppBar'
import { LoadState } from '../../components/LoadState'
import { toSen } from '../../lib/money'
import { newRequestId } from '../../lib/requestId'
import { catalogPaths, type CatalogItem, type Folder, type Tab } from '../catalog/types'
import { loadEditableOrder, submitCheckout } from '../checkout/checkoutApi'
import { PaymentScreen } from '../checkout/PaymentScreen'
import { CustomerPicker } from '../customers/CustomerPicker'
import { useCustomerDisplay } from '../display/useCustomerDisplay'
import { CartPanel, type CartNotice } from './CartPanel'
import { CustomAmountPad } from './CustomAmountPad'
import { DiscountApprovalDialog } from './DiscountApprovalDialog'
import { FolderGrid } from './FolderGrid'
import { FolderItems } from './FolderItems'
import { ItemDialog } from './ItemDialog'
import { OrderDiscountsGrid } from './OrderDiscountsGrid'
import { cartItemCount, cartReducer, cartTotalSen, discountInSale, emptyCart, needsDiscountApproval, type CartLine } from './cart'
import { useCartQuote } from './useCartQuote'
import { useRegister } from '../register/registerApi'
import { appBarTab } from '../../styles/ui'

/** What the left side shows: a product tab, the order discounts (%), or the custom amount keypad. */
type View = { kind: 'tab'; tabId: number | null } | { kind: 'discounts' } | { kind: 'keypad' }

/** The item dialog: adding an item that has options, or editing a line already in the sale. */
type Editing = { mode: 'add'; line: Omit<CartLine, 'key'>; withOptions: boolean } | { mode: 'edit'; line: CartLine }

interface SellScreenProps {
  onOpenMenu: () => void
  /** A manager approves department comps themselves; a cashier needs a manager's PIN. */
  isManager?: boolean
  /** The customer display paired with this till, if any. */
  displayId?: number | null
  /** The paired display was disconnected elsewhere. */
  onDisplayGone?: () => void
  /** "Edit Order" from Past Orders: load that placed order into the cart. */
  editRequest?: { orderId: number; seq: number } | null
  /** An unpaid order was voided in Past Orders: if the cart is editing it, or a notice is about it, they go. */
  voidedOrder?: { orderId: number; seq: number } | null
  /** A sale was paid and the cashier saw it through (or it closed itself). */
  onSaleComplete?: () => void
}

const noop = () => {}

/** Point of Sale screen: quickgrid on the left, current sale on the right, tab bar at the bottom. */
export function SellScreen({
  onOpenMenu, isManager = false, displayId = null, onDisplayGone = noop, editRequest = null, voidedOrder = null,
  onSaleComplete = noop,
}: SellScreenProps) {
  const tabs = useApi<Tab[]>(catalogPaths.tabs)
  const [view, setView] = useState<View>({ kind: 'tab', tabId: null })
  const [openFolder, setOpenFolder] = useState<Folder | null>(null)
  const [cart, dispatch] = useReducer(cartReducer, emptyCart)
  const quote = useCartQuote(cart)
  const register = useRegister()
  const [editing, setEditing] = useState<Editing | null>(null)
  const [paying, setPaying] = useState(false)
  // A member who logged in or signed up on the customer display joins the sale (not mid-payment).
  const display = useCustomerDisplay(displayId, cart, (member) => {
    if (paying) return false
    dispatch({ type: 'setCustomer', customer: { id: member.customerId, name: member.fullName, phone: member.phone } })
    setNotice({ tone: 'info', text: `${member.fullName} ${member.newMember ? 'signed up' : 'logged in'}` })
    return true
  }, onDisplayGone)
  const [placing, setPlacing] = useState(false)
  const [choosingCustomer, setChoosingCustomer] = useState(false)
  const [notice, setNotice] = useState<CartNotice | null>(null)
  // A department comp: the manager's approval is asked for when the cashier presses Pay or Place Order.
  const [approvingFor, setApprovingFor] = useState<'pay' | null>(null)
  const [approvalToken, setApprovalToken] = useState<string | undefined>(undefined)
  // Same cart → same id, so retrying a Place Order that may have reached the server is safe.
  const placeRequestId = useMemo(() => (cart.lines.length > 0 ? newRequestId() : ''), [cart])

  // "Edit Order": the placed order replaces whatever was in the cart.
  useEffect(() => {
    if (!editRequest) return
    let current = true
    loadEditableOrder(editRequest.orderId)
      .then((order) => {
        if (!current) return
        dispatch({ type: 'loadOrder', order })
        setPaying(false)
        setOpenFolder(null)
        setApprovalToken(undefined)
        setNotice(null)
      })
      .catch((error: unknown) => {
        if (current) setNotice({ tone: 'error', text: error instanceof ApiError ? error.message : 'Order not loaded' })
      })
    return () => {
      current = false
    }
  }, [editRequest])

  // A voided order can't be edited or paid any more: drop it from the cart.
  const editingOrder = useRef(cart.editing)
  useEffect(() => {
    editingOrder.current = cart.editing
  })
  useEffect(() => {
    if (!voidedOrder) return
    const editing = editingOrder.current
    if (editing?.orderId === voidedOrder.orderId) {
      dispatch({ type: 'clear' })
      setPaying(false)
      setApprovalToken(undefined)
      setNotice({ tone: 'info', text: `${editing.orderNumber} voided` })
    }
  }, [voidedOrder])

  const tabList = tabs.status === 'success' ? tabs.data : []
  const activeTab = view.kind === 'tab' ? (tabList.find((tab) => tab.id === view.tabId) ?? tabList[0]) : undefined

  function startNewSale() {
    dispatch({ type: 'clear' })
    setPaying(false)
    setOpenFolder(null)
    setApprovalToken(undefined)
  }

  /** Simple items go straight into the sale; items with options open the item dialog first. */
  function tapItem(item: CatalogItem) {
    if (!item.hasOptions) {
      dispatch({ type: 'addItem', item })
      return
    }
    setEditing({
      mode: 'add',
      withOptions: true,
      line: {
        itemId: item.id, name: item.name, unitPriceSen: toSen(item.price), modifiers: [], quantity: 1, note: '', discounts: [],
        promotion: item.promotion ?? null, countsForPromotions: item.countsForPromotions ?? false,
      },
    })
  }

  /**
   * Pay, after a manager's approval when a department comp is in the sale. Place Order goes ahead
   * whatever is in the sale: the approval and the promotion rules apply when it is paid.
   */
  function proceed(action: 'pay' | 'place') {
    setNotice(null)
    if (action === 'place') {
      void placeOrder()
    } else if (needsDiscountApproval(cart) && !isManager) {
      setApprovingFor('pay')
    } else {
      setPaying(true)
    }
  }

  function approved(token: string) {
    setApprovingFor(null)
    setApprovalToken(token)
    setPaying(true)
  }

  /** "Place Order": save the sale unpaid; it is paid later from Past Orders. */
  async function placeOrder(token = approvalToken) {
    setPlacing(true)
    setNotice(null)
    try {
      await submitCheckout(cart, 'PLACE_ORDER', [], placeRequestId, token)
      // No notice: the open-orders badge counts it.
      startNewSale()
      register.refresh()
    } catch (error) {
      setNotice({ tone: 'error', text: error instanceof ApiError ? error.message : 'Place Order failed' })
    } finally {
      setPlacing(false)
    }
  }

  let catalog
  if (view.kind === 'discounts') {
    catalog = (
      <OrderDiscountsGrid
        selected={cart.orderDiscounts}
        lineDiscount={discountInSale({ ...cart, orderDiscounts: [] }) !== null}
        onChange={(discounts) => dispatch({ type: 'setOrderDiscounts', discounts })}
      />
    )
  } else if (view.kind === 'keypad') {
    catalog = <CustomAmountPad onAdd={(amountSen) => dispatch({ type: 'addCustomAmount', amountSen })} />
  } else if (tabs.status !== 'success') {
    catalog = <LoadState status={tabs.status} message={tabs.status === 'error' ? tabs.message : undefined} onRetry={tabs.reload} />
  } else if (!activeTab) {
    catalog = <p className="p-10 text-center text-sm text-zinc-500">No catalogue has been set up yet.</p>
  } else if (openFolder) {
    catalog = <FolderItems key={openFolder.categoryId} folder={openFolder} onBack={() => setOpenFolder(null)} onAddItem={tapItem} />
  } else {
    catalog = <FolderGrid folders={activeTab.folders} onOpen={setOpenFolder} />
  }

  return (
    <div className="grid h-dvh grid-cols-[minmax(0,1fr)_320px] grid-rows-[minmax(0,1fr)_auto] max-md:grid-cols-[minmax(0,1fr)] max-md:grid-rows-[minmax(0,1fr)_minmax(0,40dvh)_auto]">
      <section className="overflow-y-auto bg-zinc-100 p-5 max-md:px-3 max-md:py-3" aria-label="Products">
        {catalog}
      </section>

      <CartPanel
        cart={cart}
        dispatch={dispatch}
        busy={placing}
        notice={notice}
        onPay={() => proceed('pay')}
        onPlaceOrder={() => proceed('place')}
        onChooseCustomer={() => setChoosingCustomer(true)}
        onEditLine={(line) => setEditing({ mode: 'edit', line })}
        quote={quote}
      />

      {editing && (
        <ItemDialog
          initial={editing.line}
          mode={editing.mode}
          discountElsewhere={discountInSale(cart, editing.mode === 'edit' ? editing.line.key : undefined) !== null}
          withOptions={editing.mode === 'add' ? editing.withOptions : editing.line.modifiers.length > 0}
          onSave={(line) => {
            if (editing.mode === 'add') dispatch({ type: 'addLine', line })
            else dispatch({ type: 'updateLine', key: editing.line.key, line })
            setEditing(null)
          }}
          onRemove={
            editing.mode === 'edit'
              ? () => {
                  dispatch({ type: 'removeLine', key: editing.line.key })
                  setEditing(null)
                }
              : undefined
          }
          onCancel={() => setEditing(null)}
        />
      )}

      {choosingCustomer && (
        <CustomerPicker
          onSelect={(customer) => {
            dispatch({ type: 'setCustomer', customer })
            setChoosingCustomer(false)
          }}
          onCancel={() => setChoosingCustomer(false)}
        />
      )}

      {approvingFor && (
        <DiscountApprovalDialog
          discountName={[...cart.orderDiscounts, ...cart.lines.flatMap((line) => line.discounts)]
            .find((discount) => discount.requiresApproval)?.name ?? 'this discount'}
          onApproved={approved}
          onCancel={() => setApprovingFor(null)}
        />
      )}

      {paying && (
        <PaymentScreen
          amountDueSen={cartTotalSen(cart)}
          totalLabel={`Total (${cartItemCount(cart)} items)`}
          onSubmit={async (payments, requestId) => {
            const order = await submitCheckout(cart, 'PAY', payments, requestId, approvalToken)
            display.showThankYou(cart)
            return order
          }}
          onCancel={() => setPaying(false)}
          onDone={() => {
            startNewSale()
            onSaleComplete()
          }}
          doneLabel="Done"
        />
      )}

      <AppBar label="Product tabs" onOpenMenu={onOpenMenu}>
        {tabList.map((tab) => (
          <button
            key={tab.id}
            type="button"
            className={appBarTab}
            aria-current={tab.id === activeTab?.id ? 'page' : undefined}
            onClick={() => {
              setView({ kind: 'tab', tabId: tab.id })
              setOpenFolder(null)
            }}
          >
            {tab.name}
          </button>
        ))}
        <button
          type="button"
          className={appBarTab}
          aria-label="Order discounts"
          aria-current={view.kind === 'discounts' ? 'page' : undefined}
          onClick={() => setView({ kind: 'discounts' })}
        >
          %
        </button>
        <button
          type="button"
          className={appBarTab}
          aria-label="Custom amount"
          aria-current={view.kind === 'keypad' ? 'page' : undefined}
          onClick={() => setView({ kind: 'keypad' })}
        >
          123
        </button>
      </AppBar>
    </div>
  )
}
