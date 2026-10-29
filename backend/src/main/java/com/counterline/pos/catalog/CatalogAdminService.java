package com.counterline.pos.catalog;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.outlet.OutletProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Head office edits the catalogue from the dashboard: item names, prices and whether they are on
 * sale, and new items. Every outlet sells the same items at the same prices; past sales keep the
 * name and price they were sold at (order lines are snapshots).
 */
@Service
@Transactional
public class CatalogAdminService {

    private static final Logger log = LoggerFactory.getLogger(CatalogAdminService.class);

    /** The till grid is 5 by 5 (category_grid_cell_ck). */
    private static final int GRID_SIZE = 5;

    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final QuickgridTabRepository tabRepository;
    private final JdbcTemplate jdbc;

    private final OutletProperties outlet;
    private final Clock clock;

    CatalogAdminService(ItemRepository itemRepository, CategoryRepository categoryRepository,
                        QuickgridTabRepository tabRepository, JdbcTemplate jdbc, OutletProperties outlet, Clock clock) {
        this.jdbc = jdbc;
        this.itemRepository = itemRepository;
        this.categoryRepository = categoryRepository;
        this.tabRepository = tabRepository;
        this.outlet = outlet;
        this.clock = clock;
    }

    /**
     * {@code promotion}: the promotion that makes a promotional item free, if any. {@code seasonal}:
     * marked as a seasonal flavour from {@code seasonalFrom} to {@code seasonalUntil};
     * {@code seasonalNow}: and today is one of those days.
     */
    public record AdminItem(Long id, String name, BigDecimal price, boolean active, String promotion, boolean seasonal,
                            LocalDate seasonalFrom, LocalDate seasonalUntil, boolean seasonalNow) {

        static AdminItem from(Item item, LocalDate today) {
            return new AdminItem(item.getId(), item.getName(), item.getPrice(), item.isActive(),
                    item.getPromotion() == null ? null : item.getPromotion().getName(), item.isSeasonal(),
                    item.getSeasonalFrom(), item.getSeasonalUntil(), item.isSeasonalOn(today));
        }
    }

    /** A folder with every item in it, on sale or not. {@code tab} is null for a folder off the grid. */
    public record AdminCategory(Long id, String name, String tab, Integer row, Integer col, boolean countsForPromotions,
                                List<AdminItem> items) {

        static AdminCategory from(Category category, List<AdminItem> items) {
            return new AdminCategory(category.getId(), category.getName(),
                    category.getTab() == null ? null : category.getTab().getName(),
                    category.getGridRow() == null ? null : category.getGridRow().intValue(),
                    category.getGridCol() == null ? null : category.getGridCol().intValue(),
                    category.isCountsForPromotions(), items);
        }
    }

    /** A quick-grid tab with the number of folders on it. */
    public record AdminTab(Long id, String name, int sortOrder, long folders) {
    }

    /** {@code DELETED}, or {@code DEACTIVATED} when the item was sold and its history is kept. */
    public record Removal(String outcome) {
    }

    /** The ids in the order wanted. */
    public record OrderRequest(List<Long> ids) {
    }

    public record TabRequest(@NotBlank @Size(max = 60) String name) {
    }

    /**
     * Changes a folder. {@code tab} blank or absent takes it off the till grid. On a tab, {@code row} and
     * {@code col} (1 to 5, both or neither) choose the cell; without them it keeps its cell on the same tab,
     * or takes the first free cell of another. {@code countsForPromotions} is unchanged when absent.
     */
    public record FolderUpdate(@NotBlank @Size(max = 80) String name, @Size(max = 60) String tab, Integer row,
                               Integer col, Boolean countsForPromotions) {
    }

    public record ItemRequest(
            Long categoryId,
            @NotBlank @Size(max = 120) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
            Boolean active,
            /* A seasonal flavour (earns the seasonal-flavour promotion's free item) for a limited time:
               until seasonalUntil (required), from seasonalFrom (or now). Unchanged when absent. */
            Boolean seasonal,
            LocalDate seasonalFrom,
            LocalDate seasonalUntil) {
    }

    /** A new folder: its name, and the quick-grid tab to place it on (created if there is none by that name). */
    public record FolderRequest(@NotBlank @Size(max = 80) String name, @NotBlank @Size(max = 60) String tab) {
    }

    /** Every folder, including empty ones, in till order, each with its items. */
    @Transactional(readOnly = true)
    public List<AdminCategory> catalogue() {
        Map<Long, List<AdminItem>> byCategory = new LinkedHashMap<>();
        for (Item item : itemRepository.findAllInTillOrder()) {
            byCategory.computeIfAbsent(item.getCategory().getId(), id -> new java.util.ArrayList<>())
                    .add(AdminItem.from(item, today()));
        }
        return categoryRepository.findAllInTillOrder().stream()
                .map(category -> AdminCategory.from(category, byCategory.getOrDefault(category.getId(), List.of())))
                .toList();
    }

    /**
     * Adds a folder to the till grid: on the tab of that name (created after the existing tabs if it
     * does not exist), in the first free cell reading row by row.
     */
    public AdminCategory createFolder(FolderRequest request) {
        String name = request.name().strip();
        String tabName = request.tab().strip();
        if (name.isEmpty() || tabName.isEmpty()) {
            throw new BadRequestException("Give the folder and the tab a name");
        }
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("A folder called " + name + " already exists");
        }
        QuickgridTab tab = findOrCreateTab(tabName);
        short[] cell = firstFreeCell(tab, null);
        Category category = saveFolder(new Category(name, tab, cell[0], cell[1]));
        log.info("Folder {} ({}) added to tab {} at row {}, column {}", category.getId(), name, tab.getName(), cell[0], cell[1]);
        return AdminCategory.from(category, List.of());
    }

    /** Renames a folder, moves it to another tab or cell, takes it off the grid, or changes whether it counts for promotions. */
    public AdminCategory updateFolder(Long folderId, FolderUpdate request) {
        Category category = requireFolder(folderId);
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new BadRequestException("Give the folder a name");
        }
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(name, folderId)) {
            throw new ConflictException("A folder called " + name + " already exists");
        }
        String tabName = request.tab() == null ? "" : request.tab().strip();
        if (tabName.isEmpty()) {
            if (request.row() != null || request.col() != null) {
                throw new BadRequestException("A folder off the till grid has no grid cell");
            }
            category.removeFromGrid();
        } else {
            QuickgridTab tab = findOrCreateTab(tabName);
            short[] cell;
            if (request.row() != null || request.col() != null) {
                cell = requestedCell(tab, folderId, request.row(), request.col());
            } else if (category.getTab() != null && category.getTab().getId().equals(tab.getId())) {
                cell = new short[]{category.getGridRow(), category.getGridCol()};
            } else {
                cell = firstFreeCell(tab, folderId);
            }
            category.placeOn(tab, cell[0], cell[1]);
        }
        category.rename(name);
        if (request.countsForPromotions() != null) {
            category.setCountsForPromotions(request.countsForPromotions());
        }
        category = saveFolder(category);
        log.info("Folder {} edited: {} on {}", folderId, name, category.getTab() == null ? "no tab" : category.getTab().getName());
        return AdminCategory.from(category, adminItems(folderId));
    }

    /** Deletes a folder that has no items (past sales keep the folder name they were sold under). */
    public void deleteFolder(Long folderId) {
        Category category = requireFolder(folderId);
        long items = itemRepository.countByCategoryId(folderId);
        if (items > 0) {
            throw new ConflictException(category.getName() + " still has " + items
                    + (items == 1 ? " item" : " items") + ". Move or remove them first");
        }
        categoryRepository.delete(category);
        categoryRepository.flush();
        log.info("Folder {} ({}) deleted", folderId, category.getName());
    }

    /** Puts a folder's items in the order wanted. */
    public List<AdminItem> reorderItems(Long folderId, OrderRequest request) {
        requireFolder(folderId);
        SortOrders.apply(itemRepository.findByCategoryIdOrderBySortOrderAsc(folderId), request.ids(), Item::getId,
                Item::setSortOrder, itemRepository::flush, "items");
        return adminItems(folderId);
    }

    @Transactional(readOnly = true)
    public List<AdminTab> tabs() {
        return tabRepository.findAllByOrderBySortOrderAsc().stream()
                .map(tab -> new AdminTab(tab.getId(), tab.getName(), tab.getSortOrder(), categoryRepository.countByTabId(tab.getId())))
                .toList();
    }

    public AdminTab renameTab(Long tabId, TabRequest request) {
        QuickgridTab tab = requireTab(tabId);
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new BadRequestException("Give the tab a name");
        }
        if (tabRepository.existsByNameIgnoreCaseAndIdNot(name, tabId)) {
            throw new ConflictException("A tab called " + name + " already exists");
        }
        tab.rename(name);
        tabRepository.flush();
        return new AdminTab(tab.getId(), tab.getName(), tab.getSortOrder(), categoryRepository.countByTabId(tabId));
    }

    public List<AdminTab> reorderTabs(OrderRequest request) {
        SortOrders.apply(tabRepository.findAllByOrderBySortOrderAsc(), request.ids(), QuickgridTab::getId,
                (tab, order) -> tab.setSortOrder(order.shortValue()), tabRepository::flush, "tabs");
        return tabs();
    }

    /** Deletes a tab that has no folders. */
    public void deleteTab(Long tabId) {
        QuickgridTab tab = requireTab(tabId);
        long folders = categoryRepository.countByTabId(tabId);
        if (folders > 0) {
            throw new ConflictException("The " + tab.getName() + " tab still has " + folders
                    + (folders == 1 ? " folder" : " folders") + ". Move or remove them first");
        }
        tabRepository.delete(tab);
        tabRepository.flush();
        log.info("Tab {} ({}) deleted", tabId, tab.getName());
    }

    /**
     * Removes an item: deleted (with its option groups) if it has never been sold, otherwise taken off
     * sale so every past sale, report and reward keeps referring to it.
     */
    public Removal removeItem(Long itemId) {
        Item item = requireItem(itemId);
        boolean sold = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sale_order_line WHERE item_id = ?)", Boolean.class, itemId));
        if (sold) {
            item.edit(item.getName(), item.getPrice(), false);
            itemRepository.flush();
            log.info("Item {} taken off sale (it has been sold)", itemId);
            return new Removal("DEACTIVATED");
        }
        itemRepository.flush();
        jdbc.update("DELETE FROM modifier_option WHERE group_id IN (SELECT id FROM modifier_group WHERE item_id = ?)", itemId);
        jdbc.update("DELETE FROM modifier_group WHERE item_id = ?", itemId);
        itemRepository.delete(item);
        itemRepository.flush();
        log.info("Item {} ({}) deleted", itemId, item.getName());
        return new Removal("DELETED");
    }

    private QuickgridTab findOrCreateTab(String tabName) {
        QuickgridTab tab = tabRepository.findByNameIgnoreCase(tabName).orElse(null);
        return tab != null ? tab : tabRepository.save(new QuickgridTab(tabName, (short) (tabRepository.maxSortOrder() + 1)));
    }

    /** The first free cell of the tab, row by row, ignoring the folder being moved. */
    private short[] firstFreeCell(QuickgridTab tab, Long exceptFolderId) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Category placed : categoryRepository.findByTabId(tab.getId())) {
            if (!placed.getId().equals(exceptFolderId)) {
                taken.add(placed.getGridRow() + ":" + placed.getGridCol());
            }
        }
        for (int cell = 0; cell < GRID_SIZE * GRID_SIZE; cell++) {
            short row = (short) (cell / GRID_SIZE + 1);
            short col = (short) (cell % GRID_SIZE + 1);
            if (!taken.contains(row + ":" + col)) {
                return new short[]{row, col};
            }
        }
        throw new ConflictException("The " + tab.getName() + " tab is full (" + GRID_SIZE + " by " + GRID_SIZE
                + " folders). Choose another tab");
    }

    private short[] requestedCell(QuickgridTab tab, Long folderId, Integer row, Integer col) {
        if (row == null || col == null || row < 1 || row > GRID_SIZE || col < 1 || col > GRID_SIZE) {
            throw new BadRequestException("Give both a row and a column, each from 1 to " + GRID_SIZE);
        }
        boolean taken = categoryRepository.findByTabId(tab.getId()).stream()
                .anyMatch(other -> !other.getId().equals(folderId)
                        && other.getGridRow() == row.shortValue() && other.getGridCol() == col.shortValue());
        if (taken) {
            throw new ConflictException("Row " + row + ", column " + col + " of the " + tab.getName() + " tab is taken");
        }
        return new short[]{row.shortValue(), col.shortValue()};
    }

    private Category saveFolder(Category category) {
        try {
            return categoryRepository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("That folder name or grid cell was just taken. Try again");
        }
    }

    private List<AdminItem> adminItems(Long folderId) {
        return itemRepository.findByCategoryIdOrderBySortOrderAsc(folderId).stream()
                .map(item -> AdminItem.from(item, today())).toList();
    }

    private Category requireFolder(Long folderId) {
        return categoryRepository.findById(folderId)
                .orElseThrow(() -> new ResourceNotFoundException("Folder " + folderId + " not found"));
    }

    private QuickgridTab requireTab(Long tabId) {
        return tabRepository.findById(tabId)
                .orElseThrow(() -> new ResourceNotFoundException("Tab " + tabId + " not found"));
    }

    private Item requireItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item " + itemId + " not found"));
    }

    public AdminItem create(ItemRequest request) {
        if (request.categoryId() == null) {
            throw new BadRequestException("Choose a folder for the item");
        }
        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category " + request.categoryId() + " not found"));
        String name = request.name().strip();
        if (itemRepository.existsByCategoryIdAndNameIgnoreCase(category.getId(), name)) {
            throw new ConflictException(category.getName() + " already has an item called " + name);
        }
        Item item = new Item(category, name, request.price(), itemRepository.maxSortOrder(category.getId()) + 1);
        setSeasonal(item, request);
        item = itemRepository.saveAndFlush(item);
        log.info("Item {} ({}) added to {} at RM{}", item.getId(), name, category.getName(), item.getPrice());
        return AdminItem.from(item, today());
    }

    public AdminItem update(Long itemId, ItemRequest request) {
        Item item = requireItem(itemId);
        String name = request.name().strip();
        // A categoryId different from the item's own moves it to the end of that folder.
        Category destination = request.categoryId() == null || request.categoryId().equals(item.getCategory().getId())
                ? item.getCategory() : requireFolder(request.categoryId());
        if (itemRepository.existsByCategoryIdAndNameIgnoreCaseAndIdNot(destination.getId(), name, itemId)) {
            throw new ConflictException(destination.getName() + " already has an item called " + name);
        }
        BigDecimal oldPrice = item.getPrice();
        if (!destination.getId().equals(item.getCategory().getId())) {
            item.moveTo(destination, itemRepository.maxSortOrder(destination.getId()) + 1);
        }
        item.edit(name, request.price(), request.active() == null || request.active());
        setSeasonal(item, request);
        log.info("Item {} edited: {} RM{} → RM{}, active {}, seasonal {} ({} to {})", itemId, name, oldPrice,
                request.price(), item.isActive(), item.isSeasonal(), item.getSeasonalFrom(), item.getSeasonalUntil());
        return AdminItem.from(item, today());
    }

    /** A seasonal flavour is for a limited time, so it needs its last day. */
    private static void setSeasonal(Item item, ItemRequest request) {
        if (request.seasonal() == null) {
            return;
        }
        if (request.seasonal()) {
            if (request.seasonalUntil() == null) {
                throw new BadRequestException("Set the last day " + item.getName() + " is a seasonal flavour");
            }
            if (request.seasonalFrom() != null && request.seasonalFrom().isAfter(request.seasonalUntil())) {
                throw new BadRequestException("The seasonal flavour's first day is after its last day");
            }
        }
        item.setSeasonal(request.seasonal(), request.seasonalFrom(), request.seasonalUntil());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(outlet.timeZone()));
    }
}
