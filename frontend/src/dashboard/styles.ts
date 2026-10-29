/*
 * The dashboard's classes, in the POS's look: white cards on grey, the till's segmented tabs and
 * tiles, small-caps section titles, and soft white tables.
 */
/** A small-caps heading, as over a till order's items ("ORDER ITEMS"). */
export const sectionTitle = 'mb-3 text-xs font-semibold tracking-wider text-zinc-500 uppercase'

/** A text field or select, like the till's fields. */
export const field = [
  'rounded-xl border-0 bg-white px-3 py-2 text-sm text-zinc-900 shadow-xs ring-1 ring-zinc-200 transition ring-inset',
  'placeholder:text-zinc-400 focus:ring-2 focus:ring-indigo-500 focus:outline-none disabled:bg-zinc-50 disabled:text-zinc-500',
].join(' ')

/** A checkbox, sized for a finger. */
export const checkbox = 'size-[1.15rem] rounded accent-indigo-600'

/** A result banner across a page: green when it worked, red when it did not. */
export function noticeClass(tone: 'ok' | 'error') {
  return `rounded-xl px-4 py-2.5 text-sm font-medium ring-1 ring-inset ${tone === 'ok' ? 'bg-green-50 text-green-700 ring-green-200' : 'bg-red-50 text-red-700 ring-red-200'}`
}

/** A small note under a value. */
export const note = 'mt-0.5 block text-xs text-zinc-500'

/** The frame around a table: a white card, scrolling sideways when it must. */
export const tableFrame = 'overflow-x-auto rounded-2xl bg-white shadow-sm ring-1 ring-zinc-900/5'

/** A list of rows: small-caps headings on a tint, a hairline between rows, a tint on hover. */
export const table = [
  'w-full border-collapse text-sm',
  '[&_th]:border-b [&_th]:border-zinc-200 [&_th]:bg-zinc-50 [&_th]:px-4 [&_th]:py-3 [&_th]:text-left [&_th]:text-xs [&_th]:font-semibold',
  '[&_th]:tracking-wider [&_th]:whitespace-nowrap [&_th]:text-zinc-500 [&_th]:uppercase',
  '[&_td]:border-b [&_td]:border-zinc-100 [&_td]:px-4 [&_td]:py-3 [&_td]:align-middle [&_td]:text-zinc-700',
  '[&_tbody_tr]:transition [&_tbody_tr:hover]:bg-zinc-50/70 [&_tbody_tr:last-child_td]:border-b-0',
].join(' ')

/** On a phone, an editable table's rows become small cards, each field with its label. */
export const tableAsCards = [
  'max-md:block max-md:[&_tbody]:block max-md:[&_thead]:hidden',
  'max-md:[&_tr]:flex max-md:[&_tr]:flex-wrap max-md:[&_tr]:items-end max-md:[&_tr]:gap-x-3 max-md:[&_tr]:gap-y-2',
  'max-md:[&_tr]:border-b max-md:[&_tr]:border-zinc-100 max-md:[&_tr]:p-4 max-md:[&_tbody_tr:last-child]:border-b-0',
  'max-md:[&_td]:border-0 max-md:[&_td]:p-0 max-md:[&_td:first-child]:basis-full max-md:[&_td:last-child]:ml-auto',
  'max-md:[&_td[data-label]]:before:mb-1 max-md:[&_td[data-label]]:before:block max-md:[&_td[data-label]]:before:text-xs',
  'max-md:[&_td[data-label]]:before:font-medium max-md:[&_td[data-label]]:before:text-zinc-500 max-md:[&_td[data-label]]:before:content-[attr(data-label)]',
].join(' ')
