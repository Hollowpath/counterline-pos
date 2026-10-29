package com.counterline.pos.catalog;

import com.counterline.pos.catalog.CatalogDtos.CategoryItemsResponse;
import com.counterline.pos.catalog.CatalogDtos.FolderResponse;
import com.counterline.pos.catalog.CatalogDtos.ItemOptionsResponse;
import com.counterline.pos.catalog.CatalogDtos.ItemResponse;
import com.counterline.pos.catalog.CatalogDtos.ModifierGroupResponse;
import com.counterline.pos.catalog.CatalogDtos.ModifierOptionResponse;
import com.counterline.pos.catalog.CatalogDtos.PromotionSummary;
import com.counterline.pos.catalog.CatalogDtos.TabResponse;
import com.counterline.pos.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final QuickgridTabRepository tabRepository;
    private final CategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final ModifierGroupRepository modifierGroupRepository;

    CatalogService(QuickgridTabRepository tabRepository,
                   CategoryRepository categoryRepository,
                   ItemRepository itemRepository,
                   ModifierGroupRepository modifierGroupRepository) {
        this.tabRepository = tabRepository;
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.modifierGroupRepository = modifierGroupRepository;
    }

    /** All tabs in display order, each with its folder grid. Two queries regardless of catalogue size. */
    public List<TabResponse> getTabs() {
        Map<Long, List<FolderResponse>> foldersByTab = categoryRepository.findFolderTiles().stream()
                .collect(Collectors.groupingBy(
                        FolderTile::tabId,
                        Collectors.mapping(
                                tile -> new FolderResponse(tile.categoryId(), tile.name(),
                                        tile.gridRow(), tile.gridCol(), tile.itemCount()),
                                Collectors.toList())));

        return tabRepository.findAllByOrderBySortOrderAsc().stream()
                .map(tab -> new TabResponse(tab.getId(), tab.getName(),
                        foldersByTab.getOrDefault(tab.getId(), List.of())))
                .toList();
    }

    /** Active items with the given ids, keyed by id. Unknown or inactive ids are simply absent. */
    public Map<Long, Item> getActiveItems(Collection<Long> itemIds) {
        return itemRepository.findByIdInAndActiveTrue(itemIds).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
    }

    /** Option groups (with options) of the given items, keyed by item id; items without options are absent. */
    public Map<Long, List<ModifierGroup>> getModifierGroups(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        return modifierGroupRepository.findWithOptionsByItemIds(itemIds).stream()
                .filter(group -> group.getOptions().stream().anyMatch(ModifierOption::isActive))
                .collect(Collectors.groupingBy(ModifierGroup::getItemId));
    }

    /** Names of the items a promotion makes free (e.g. the new-member gift). */
    public List<String> getItemNamesForPromotion(Long promotionId) {
        return itemRepository.findByPromotionIdAndActiveTrueOrderBySortOrderAsc(promotionId).stream()
                .map(Item::getName)
                .toList();
    }

    public String getCategoryName(Long categoryId) {
        return categoryRepository.findById(categoryId).map(Category::getName)
                .orElseThrow(() -> new ResourceNotFoundException("Category " + categoryId + " not found"));
    }

    public CategoryItemsResponse getCategoryItems(Long categoryId) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Category " + categoryId + " not found"));

        List<Item> items = itemRepository.findByCategoryIdAndActiveTrueOrderBySortOrderAsc(categoryId);
        Set<Long> withOptions = items.isEmpty() ? Set.of()
                : modifierGroupRepository.findItemIdsWithGroups(items.stream().map(Item::getId).toList());

        return new CategoryItemsResponse(category.getId(), category.getName(), items.stream()
                .map(item -> new ItemResponse(item.getId(), item.getName(), item.getPrice(), item.getImageUrl(),
                        withOptions.contains(item.getId()), PromotionSummary.of(item.getPromotion()),
                        category.isCountsForPromotions()))
                .toList());
    }

    /** What the item dialog offers: the item's option groups and their active options. */
    public ItemOptionsResponse getItemOptions(Long itemId) {
        Item item = getActiveItems(List.of(itemId)).get(itemId);
        if (item == null) {
            throw new ResourceNotFoundException("Item " + itemId + " not found");
        }
        List<ModifierGroupResponse> groups = getModifierGroups(List.of(itemId)).getOrDefault(itemId, List.of()).stream()
                .map(group -> new ModifierGroupResponse(group.getId(), group.getName(), group.getChooseExactly(),
                        group.getOptions().stream()
                                .filter(ModifierOption::isActive)
                                .map(option -> new ModifierOptionResponse(option.getId(), option.getName(), option.getPrice()))
                                .toList()))
                .toList();
        return new ItemOptionsResponse(item.getId(), item.getName(), item.getPrice(), groups);
    }
}
