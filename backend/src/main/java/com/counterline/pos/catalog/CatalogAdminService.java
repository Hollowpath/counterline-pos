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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final OutletProperties outlet;
    private final Clock clock;

    CatalogAdminService(ItemRepository itemRepository, CategoryRepository categoryRepository,
                        QuickgridTabRepository tabRepository, OutletProperties outlet, Clock clock) {
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
    public record AdminCategory(Long id, String name, String tab, List<AdminItem> items) {
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
                .map(category -> new AdminCategory(category.getId(), category.getName(),
                        category.getTab() == null ? null : category.getTab().getName(),
                        byCategory.getOrDefault(category.getId(), List.of())))
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
        QuickgridTab tab = tabRepository.findByNameIgnoreCase(tabName).orElse(null);
        short row = 1;
        short col = 1;
        if (tab == null) {
            tab = tabRepository.save(new QuickgridTab(tabName, (short) (tabRepository.maxSortOrder() + 1)));
        } else {
            Set<String> taken = new HashSet<>();
            for (Category placed : categoryRepository.findByTabId(tab.getId())) {
                taken.add(placed.getGridRow() + ":" + placed.getGridCol());
            }
            int free = -1;
            for (int cell = 0; cell < GRID_SIZE * GRID_SIZE && free < 0; cell++) {
                if (!taken.contains((cell / GRID_SIZE + 1) + ":" + (cell % GRID_SIZE + 1))) {
                    free = cell;
                }
            }
            if (free < 0) {
                throw new ConflictException("The " + tab.getName() + " tab is full (" + GRID_SIZE + " by " + GRID_SIZE
                        + " folders). Choose another tab");
            }
            row = (short) (free / GRID_SIZE + 1);
            col = (short) (free % GRID_SIZE + 1);
        }
        Category category;
        try {
            category = categoryRepository.saveAndFlush(new Category(name, tab, row, col));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("That folder or grid cell was just taken. Try again");
        }
        log.info("Folder {} ({}) added to tab {} at row {}, column {}", category.getId(), name, tab.getName(), row, col);
        return new AdminCategory(category.getId(), category.getName(), tab.getName(), List.of());
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
        Item item = itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item " + itemId + " not found"));
        String name = request.name().strip();
        if (itemRepository.existsByCategoryIdAndNameIgnoreCaseAndIdNot(item.getCategory().getId(), name, itemId)) {
            throw new ConflictException(item.getCategory().getName() + " already has an item called " + name);
        }
        BigDecimal oldPrice = item.getPrice();
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
