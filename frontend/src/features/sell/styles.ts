/* The Point of Sale's tiles and cart, as Tailwind classes shared by its components. */

/** The grid of folder or item tiles: 5 across on the iPad, 3 on a phone or in portrait. */
export const tileGrid = 'grid grid-cols-5 gap-3 max-md:grid-cols-3 max-md:gap-2'

/** A tile on the grid: a folder, an item or a discount. */
export const tile = [
  'group relative flex min-h-26 flex-col items-center justify-center gap-2 overflow-hidden rounded-2xl bg-white p-3',
  'text-center text-[0.78rem] leading-snug font-semibold tracking-wide text-zinc-800 uppercase shadow-sm ring-1 ring-zinc-900/5',
  'transition duration-150 enabled:hover:-translate-y-0.5 enabled:hover:shadow-md enabled:hover:ring-indigo-200',
  'enabled:active:translate-y-0 enabled:active:scale-[0.97] enabled:active:bg-indigo-50',
].join(' ')

/** An item: an indigo glow along the top. */
export const itemTile = `${tile} before:absolute before:inset-x-0 before:top-0 before:h-1 before:bg-linear-to-r before:from-indigo-500 before:to-fuchsia-500 before:content-['']`

/** A discount on the % tab: lit indigo when it is in the sale. */
export const discountTile = [
  tile,
  'aria-pressed:bg-linear-to-br aria-pressed:from-indigo-600 aria-pressed:to-violet-600 aria-pressed:text-white',
  'aria-pressed:shadow-lg aria-pressed:shadow-indigo-600/30 aria-pressed:ring-indigo-600',
].join(' ')

/** A tile's price, or a discount's value (white on a chosen discount). */
export const tilePrice = [
  'rounded-full bg-zinc-100 px-2.5 py-0.5 text-xs font-semibold tracking-normal text-zinc-600 normal-case tabular-nums',
  'group-aria-pressed:bg-white/20 group-aria-pressed:text-white',
].join(' ')

/** A line under a cart line's name (options, note, discount). */
export const lineDetail = 'text-xs font-normal text-zinc-500'

/** A notice in the cart (a placed order, a member who logged in on the display). */
export const cartNotice = {
  info: 'mx-4 mb-3 animate-rise-in rounded-xl bg-indigo-50 px-3 py-2 text-[0.8rem] font-medium text-indigo-700 ring-1 ring-indigo-100 ring-inset',
  error: 'mx-4 mb-3 animate-rise-in rounded-xl bg-red-50 px-3 py-2 text-[0.8rem] font-medium text-red-700 ring-1 ring-red-100 ring-inset',
}

/** The ✕ beside the customer or an order discount. */
export const removeX = 'ml-1 inline-grid size-6 shrink-0 place-items-center rounded-full text-xs text-zinc-400 transition hover:bg-red-50 hover:text-red-600'
