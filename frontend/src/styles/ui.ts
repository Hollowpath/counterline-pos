/*
 * Tailwind classes shared by several screens of the POS, the customer display and the dashboard.
 * Screen-specific classes stay in their component.
 */

/** A dialog's buttons, at its foot on the right. */
export const dialogActions = 'mt-5 flex flex-wrap items-center justify-end gap-2'

/** A tab on the POS's bottom bar (SELLING ITEMS, %, 123): the current one a lit pill. */
export const appBarTab = [
  'my-1.5 flex-none rounded-xl px-4 text-xs font-semibold tracking-wider whitespace-nowrap text-zinc-400 uppercase transition',
  'hover:bg-white/5 hover:text-white aria-[current=page]:bg-white/10 aria-[current=page]:text-white',
  'aria-[current=page]:shadow-[inset_0_-2px_0_var(--color-indigo-400)]',
].join(' ')

/** The screen's name in the middle of the bar ("Past Orders"). */
export const appBarTitle = 'flex-1 self-center text-center text-sm font-semibold tracking-wide text-white'

/** A text field, select or search box. */
export const input = [
  'w-full rounded-xl border-0 bg-white px-3.5 py-2.5 text-base text-zinc-900 shadow-xs ring-1 ring-zinc-200 transition ring-inset',
  'placeholder:text-zinc-400 focus:ring-2 focus:ring-indigo-500 focus:outline-none disabled:bg-zinc-50 disabled:text-zinc-500',
].join(' ')

/** The login screens (staff PIN, iPad set-up, dashboard): a frosted card over a deep gradient. */
export const loginStyles = {
  screen: [
    'relative grid min-h-dvh place-items-center overflow-hidden login-backdrop p-4',
  ].join(' '),
  panel: [
    'flex w-full max-w-[24rem] flex-col items-center rounded-3xl bg-white px-7 pt-8 pb-7 shadow-2xl shadow-black/40',
    'ring-1 ring-white/20 backdrop-blur max-md:px-5',
  ].join(' '),
  title: 'text-center text-xl font-bold tracking-tight text-zinc-900',
  prompt: 'mt-1 mb-6 text-sm text-zinc-500',
  /** Why the screen is shown again, e.g. the iPad was reset from the dashboard. */
  notice: 'mt-3 w-full rounded-xl bg-orange-50 px-3.5 py-2.5 text-center text-sm font-medium text-orange-800 ring-1 ring-orange-200 ring-inset',
  link: 'mt-5 rounded-lg px-3 py-1.5 text-sm font-semibold text-indigo-600 transition hover:bg-indigo-50 hover:text-indigo-500',
}

/**
 * List-and-detail screens (Past Orders, Customers, Reports). On a phone, the list, or the selected
 * record with a back button: set `data-detail` on the screen while a record is open. Printing a
 * report prints only the detail.
 */
export const splitScreen = {
  screen: [
    'group/split grid h-dvh grid-cols-[minmax(18rem,22rem)_minmax(0,1fr)] grid-rows-[minmax(0,1fr)_auto] bg-zinc-100',
    'max-md:grid-cols-[minmax(0,1fr)] print:block print:bg-white',
  ].join(' '),
  list: [
    'flex min-h-0 flex-col gap-3 overflow-y-auto border-r border-zinc-200 bg-white p-4',
    'max-md:border-r-0 max-md:group-data-[detail=true]/split:hidden print:hidden',
  ].join(' '),
  detail: [
    'min-h-0 overflow-y-auto px-8 py-6 max-md:hidden max-md:px-4 max-md:py-4 max-md:group-data-[detail=true]/split:block',
    'print:block! print:overflow-visible print:p-0',
  ].join(' '),
  back: 'hidden max-md:group-data-[detail=true]/split:mb-4 max-md:group-data-[detail=true]/split:inline-flex',
  search: [
    'w-full rounded-xl border-0 bg-zinc-100 py-2.5 pr-3 pl-10 text-sm text-zinc-900 transition placeholder:text-zinc-400',
    'search-icon focus:bg-white focus:ring-2 focus:ring-indigo-500 focus:outline-none',
  ].join(' '),
  more: 'self-center',
}

/** A plain list (no bullets, no indent). */
export const rowList = 'list-none'

/** The till's segmented tabs (Past Orders: Open, Closed, Voided): its buttons are the tabs. */
export const segmented = [
  'flex rounded-xl bg-zinc-100 p-1 ring-1 ring-zinc-200/70 ring-inset',
  '[&>button]:flex-1 [&>button]:rounded-lg [&>button]:px-2 [&>button]:py-1.5 [&>button]:text-sm [&>button]:font-medium',
  '[&>button]:text-zinc-500 [&>button]:transition [&>button:hover]:text-zinc-900',
  '[&>button[aria-selected=true]]:bg-white [&>button[aria-selected=true]]:font-semibold [&>button[aria-selected=true]]:text-zinc-900',
  '[&>button[aria-selected=true]]:shadow-sm [&>button[aria-selected=true]]:ring-1 [&>button[aria-selected=true]]:ring-zinc-900/5',
].join(' ')

/** A record's detail: small caps section titles, label/value pairs, and rows on a white card. */
export const detail = {
  section: 'mt-7 mb-2 text-xs font-semibold tracking-wider text-zinc-500 uppercase print:mt-3 print:mb-1 print:text-[10px] print:text-black',
  info: [
    'my-5 grid gap-x-6 gap-y-3 rounded-2xl bg-white p-5 shadow-sm ring-1 ring-zinc-900/5 sm:grid-cols-2',
    '[&_div]:flex [&_div]:flex-col [&_div]:gap-0.5 [&_dt]:text-xs [&_dt]:font-medium [&_dt]:text-zinc-500 [&_dd]:text-sm [&_dd]:font-medium',
  ].join(' '),
  rows: [
    'list-none divide-y divide-zinc-100 overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-zinc-900/5',
    '[&_li]:flex [&_li]:justify-between [&_li]:gap-4 [&_li]:px-4 [&_li]:py-3 [&_li]:text-sm [&_li]:tabular-nums',
    'print:divide-y-0 print:rounded-none print:bg-transparent print:shadow-none print:ring-0',
    'print:[&_li]:bg-transparent print:[&_li]:px-0 print:[&_li]:py-[0.05rem] print:[&_li]:text-[11px]',
  ].join(' '),
  sub: 'block text-xs text-zinc-500 print:text-[10px] print:text-black',
  discount: 'text-green-600',
  total: 'font-bold text-zinc-900',
  /** A row that belongs to the one above, as on the printed reports (e.g. "Discounts & Comps"). */
  indent: 'pl-8 text-zinc-600',
}

/** A customer in a list (Customers, the sale's customer picker): the selected one lit in indigo. */
export const customerRow = {
  button: [
    'flex w-full items-center justify-between gap-3 rounded-xl px-3 py-3 text-left transition hover:bg-zinc-50',
    'aria-[current=true]:bg-zinc-200/70',
  ].join(' '),
  name: 'min-w-0 font-semibold [overflow-wrap:anywhere] text-zinc-900',
  contact: 'shrink-0 text-sm whitespace-nowrap text-zinc-500',
}

/** A count beside a menu item or tab, e.g. the open orders on Past Orders. */
export const countBadge = 'inline-grid h-5 min-w-5 animate-badge-pop place-items-center rounded-full bg-red-500 px-1.5 text-[0.7rem] leading-none font-bold text-white tabular-nums'

/** A small rounded label: a status, a count, a tag. */
export const pill = {
  base: 'inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset whitespace-nowrap',
  neutral: 'bg-zinc-100 text-zinc-600 ring-zinc-500/15',
  indigo: 'bg-indigo-50 text-indigo-700 ring-indigo-600/20',
  green: 'bg-green-50 text-green-700 ring-green-600/20',
  red: 'bg-red-50 text-red-700 ring-red-600/20',
  orange: 'bg-orange-50 text-orange-800 ring-orange-600/25',
}
