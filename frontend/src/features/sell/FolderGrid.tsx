import type { Folder } from '../catalog/types'
import { tile, tileGrid } from './styles'

interface FolderGridProps {
  folders: Folder[]
  onOpen: (folder: Folder) => void
}

/** A tab's fixed 5×5 grid; folders keep the cell they were placed in, leaving gaps between them. */
export function FolderGrid({ folders, onOpen }: FolderGridProps) {
  if (folders.length === 0) {
    return <p className="p-10 text-center text-sm text-zinc-500">No folders on this tab.</p>
  }
  return (
    <div className={`${tileGrid} min-h-full grid-rows-[repeat(5,minmax(6.5rem,1fr))] max-md:grid-rows-none`}>
      {folders.map((folder) => (
        <button
          key={folder.categoryId}
          type="button"
          // On a phone the 5x5 places don't fit 3 columns: folders flow in order.
          className={`${tile} max-md:[grid-column:auto]! max-md:[grid-row:auto]!`}
          style={{ gridRow: folder.row, gridColumn: folder.col }}
          onClick={() => onOpen(folder)}
        >
          <svg viewBox="0 0 24 24" fill="currentColor" className="size-7 text-zinc-400 transition group-hover:text-indigo-600" aria-hidden="true">
            <path d="M19.5 21a3 3 0 0 0 3-3v-4.5a3 3 0 0 0-3-3h-15a3 3 0 0 0-3 3V18a3 3 0 0 0 3 3h15ZM1.5 10.146V6a3 3 0 0 1 3-3h5.379a2.25 2.25 0 0 1 1.59.659l2.122 2.121c.14.141.331.22.53.22H19.5a3 3 0 0 1 3 3v1.146A4.483 4.483 0 0 0 19.5 9h-15a4.483 4.483 0 0 0-3 1.146Z" />
          </svg>
          <span>{folder.name}</span>
          <span className="min-w-7 rounded-full bg-zinc-100 px-2 py-0.5 text-[0.7rem] font-semibold text-zinc-500 tabular-nums" aria-label={`${folder.itemCount} items`}>
            {folder.itemCount}
          </span>
        </button>
      ))}
    </div>
  )
}
