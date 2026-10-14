import { LoadState } from '../../components/LoadState'
import { useApi } from '../../api/useApi'
import { formatRM, toSen } from '../../lib/money'
import { catalogPaths, type CatalogItem, type CategoryItems, type Folder } from '../catalog/types'
import { itemTile, tileGrid, tilePrice } from './styles'

interface FolderItemsProps {
  folder: Folder
  onBack: () => void
  onAddItem: (item: CatalogItem) => void
}

export function FolderItems({ folder, onBack, onAddItem }: FolderItemsProps) {
  const result = useApi<CategoryItems>(catalogPaths.categoryItems(folder.categoryId))

  return (
    <div>
      <header className="mb-4 grid grid-cols-[auto_1fr_auto] items-center rounded-2xl bg-white/70 p-2 shadow-xs ring-1 ring-zinc-900/5 after:w-9 after:content-['']">
        <button type="button" className="icon-btn" onClick={onBack} aria-label="Back to folders">
          ‹
        </button>
        <h2 className="text-center text-sm font-bold tracking-wider text-zinc-800 uppercase">{folder.name}</h2>
      </header>

      {result.status !== 'success' ? (
        <LoadState status={result.status} message={result.status === 'error' ? result.message : undefined} onRetry={result.reload} />
      ) : result.data.items.length === 0 ? (
        <p className="p-10 text-center text-sm text-zinc-500">This folder has no items.</p>
      ) : (
        <div className={tileGrid}>
          {result.data.items.map((item) => (
            <button key={item.id} type="button" className={itemTile} onClick={() => onAddItem(item)}>
              {item.imageUrl ? (
                <img className="size-14 object-contain drop-shadow-sm" src={item.imageUrl} alt="" />
              ) : (
                <span className={tilePrice}>{formatRM(toSen(item.price))}</span>
              )}
              <span>{item.name}</span>
              {item.promotion && (
                <span className="rounded-full bg-green-50 px-2 py-0.5 text-[0.68rem] font-semibold tracking-normal text-green-700 normal-case ring-1 ring-green-600/20 ring-inset">
                  {item.promotion.pointsCost > 0 ? `${item.promotion.pointsCost} points` : 'Free'}
                  {item.promotion.membersOnly !== false && ' · member'}
                </span>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
