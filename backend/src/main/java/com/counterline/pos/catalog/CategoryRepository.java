package com.counterline.pos.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Folder tiles for every tab, with the number of active items each folder contains.
     * Ordered row by row so reading/keyboard order matches the visual grid.
     */
    @Query("""
            select new com.counterline.pos.catalog.FolderTile(
                c.id, c.name, c.tab.id, c.gridRow, c.gridCol, count(i.id))
            from Category c
            left join Item i on i.category = c and i.active = true
            where c.tab is not null
            group by c.id, c.name, c.tab.id, c.gridRow, c.gridCol
            order by c.gridRow, c.gridCol
            """)
    List<FolderTile> findFolderTiles();

    boolean existsByNameIgnoreCase(String name);

    List<Category> findByTabId(Long tabId);

    /** Every folder, with or without items, in till order: tab, then grid cell; off-grid folders last. */
    @Query("""
            select c from Category c left join fetch c.tab t
            order by t.sortOrder nulls last, c.gridRow nulls last, c.gridCol nulls last, c.name
            """)
    List<Category> findAllInTillOrder();
}
