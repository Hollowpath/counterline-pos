// Mirrors backend CatalogDtos.

export interface Folder {
  categoryId: number
  name: string
  row: number
  col: number
  itemCount: number
}

export interface Tab {
  id: number
  name: string
  folders: Folder[]
}

/** Why a promotional item is free. */
export interface PromotionInfo {
  id: number
  name: string
  type: 'BUY_X_GET_FREE' | 'MIN_SPEND' | 'POINTS_REDEMPTION' | 'REGISTRATION_REWARD' | 'REVIEW_REWARD' | 'BIRTHDAY_REWARD'
  /** Points per item for a Member Redemption, otherwise 0. */
  pointsCost: number
  /** It needs a member on the sale (every promotion but the free recycle bags). */
  membersOnly: boolean
}

export interface CatalogItem {
  id: number
  name: string
  price: number
  imageUrl: string | null
  /** Tapping it opens the item dialog (option groups to choose from). */
  hasOptions?: boolean
  /** Set on promotional items: valued at `price`, given free when the promotion applies. */
  promotion?: PromotionInfo | null
  /** Bought, it counts towards "Buy 3" (listed first in the cart). */
  countsForPromotions?: boolean
}

export interface CategoryItems {
  categoryId: number
  name: string
  items: CatalogItem[]
}

export interface ModifierOption {
  id: number
  name: string
  price: number
}

/** "Mini Tin Flavours — CHOOSE 10": exactly chooseExactly picks across the options. */
export interface ModifierGroup {
  id: number
  name: string
  chooseExactly: number
  options: ModifierOption[]
}

export interface ItemOptions {
  itemId: number
  name: string
  price: number
  groups: ModifierGroup[]
}

export const catalogPaths = {
  tabs: '/api/v1/catalog/tabs',
  categoryItems: (categoryId: number) => `/api/v1/catalog/categories/${categoryId}/items`,
  itemOptions: (itemId: number) => `/api/v1/catalog/items/${itemId}/options`,
  discounts: (scope: 'ITEM' | 'ORDER') => `/api/v1/discounts?scope=${scope}`,
}
