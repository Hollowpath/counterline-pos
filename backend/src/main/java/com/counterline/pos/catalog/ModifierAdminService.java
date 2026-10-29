package com.counterline.pos.catalog;

import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Head office manages an item's option groups ("CHOOSE 10") and their options. Sold options are
 * snapshotted on order lines, so one that has ever been sold is deactivated, never deleted; a group
 * with no active option is hidden from the till (see CatalogService).
 */
@Service
@Transactional
public class ModifierAdminService {

    private static final Logger log = LoggerFactory.getLogger(ModifierAdminService.class);

    public record AdminOption(Long id, String name, BigDecimal price, boolean active) {
    }

    public record AdminGroup(Long id, Long itemId, String name, int chooseExactly, List<AdminOption> options) {
    }

    public record GroupRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(1) @Max(99) Integer chooseExactly) {
    }

    public record OptionRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
            Boolean active) {
    }

    /** The ids in the order wanted. */
    public record OrderRequest(List<Long> ids) {
    }

    /** {@code DELETED}, or {@code DEACTIVATED} when it was sold or used before and its history is kept. */
    public record Removal(String outcome) {
    }

    private final ItemRepository itemRepository;
    private final ModifierGroupRepository groupRepository;
    private final ModifierOptionRepository optionRepository;
    private final JdbcTemplate jdbc;

    ModifierAdminService(ItemRepository itemRepository, ModifierGroupRepository groupRepository,
                         ModifierOptionRepository optionRepository, JdbcTemplate jdbc) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.optionRepository = optionRepository;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<AdminGroup> groups(Long itemId) {
        requireItem(itemId);
        return groupRepository.findByItemIdOrderBySortOrderAsc(itemId).stream().map(this::view).toList();
    }

    public AdminGroup createGroup(Long itemId, GroupRequest request) {
        Item item = requireItem(itemId);
        String name = request.name().strip();
        requireFreeGroupName(itemId, name, null);
        ModifierGroup group = groupRepository.saveAndFlush(
                new ModifierGroup(item, name, request.chooseExactly(), groupRepository.maxSortOrder(itemId) + 1));
        log.info("Option group {} ({}) added to item {}", group.getId(), name, itemId);
        return view(group);
    }

    public AdminGroup updateGroup(Long groupId, GroupRequest request) {
        ModifierGroup group = requireGroup(groupId);
        String name = request.name().strip();
        requireFreeGroupName(group.getItemId(), name, groupId);
        group.edit(name, request.chooseExactly());
        groupRepository.flush();
        return view(group);
    }

    public List<AdminGroup> reorderGroups(Long itemId, OrderRequest request) {
        requireItem(itemId);
        SortOrders.apply(groupRepository.findByItemIdOrderBySortOrderAsc(itemId), request.ids(), ModifierGroup::getId,
                ModifierGroup::setSortOrder, groupRepository::flush, "option groups");
        return groups(itemId);
    }

    public Removal removeGroup(Long groupId) {
        ModifierGroup group = requireGroup(groupId);
        boolean sold = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sale_order_line_modifier m
                               JOIN modifier_option o ON o.id = m.option_id WHERE o.group_id = ?)
                """, Boolean.class, groupId));
        if (sold) {
            optionRepository.findByGroup(groupId).forEach(option -> option.edit(option.getName(), option.getPrice(), false));
            optionRepository.flush();
            log.info("Option group {} deactivated (its options were sold)", groupId);
            return new Removal("DEACTIVATED");
        }
        optionRepository.deleteAll(optionRepository.findByGroup(groupId));
        optionRepository.flush();
        groupRepository.delete(group);
        groupRepository.flush();
        log.info("Option group {} deleted", groupId);
        return new Removal("DELETED");
    }

    public AdminOption createOption(Long groupId, OptionRequest request) {
        ModifierGroup group = requireGroup(groupId);
        String name = request.name().strip();
        requireFreeOptionName(groupId, name, null);
        ModifierOption option = optionRepository.saveAndFlush(
                new ModifierOption(group, name, request.price(), optionRepository.maxSortOrder(groupId) + 1));
        if (request.active() != null && !request.active()) {
            option.edit(name, request.price(), false);
            optionRepository.flush();
        }
        log.info("Option {} ({}) added to group {}", option.getId(), name, groupId);
        return view(option);
    }

    public AdminOption updateOption(Long optionId, OptionRequest request) {
        ModifierOption option = requireOption(optionId);
        String name = request.name().strip();
        requireFreeOptionName(option.getGroup().getId(), name, optionId);
        option.edit(name, request.price(), request.active() == null || request.active());
        optionRepository.flush();
        return view(option);
    }

    public List<AdminOption> reorderOptions(Long groupId, OrderRequest request) {
        requireGroup(groupId);
        SortOrders.apply(optionRepository.findByGroup(groupId), request.ids(), ModifierOption::getId,
                ModifierOption::setSortOrder, optionRepository::flush, "options");
        return optionRepository.findByGroup(groupId).stream().map(this::view).toList();
    }

    public Removal removeOption(Long optionId) {
        ModifierOption option = requireOption(optionId);
        boolean sold = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sale_order_line_modifier WHERE option_id = ?)", Boolean.class, optionId));
        if (sold) {
            option.edit(option.getName(), option.getPrice(), false);
            optionRepository.flush();
            log.info("Option {} deactivated (it was sold)", optionId);
            return new Removal("DEACTIVATED");
        }
        optionRepository.delete(option);
        optionRepository.flush();
        log.info("Option {} deleted", optionId);
        return new Removal("DELETED");
    }

    private AdminGroup view(ModifierGroup group) {
        return new AdminGroup(group.getId(), group.getItemId(), group.getName(), group.getChooseExactly(),
                optionRepository.findByGroup(group.getId()).stream().map(this::view).toList());
    }

    private AdminOption view(ModifierOption option) {
        return new AdminOption(option.getId(), option.getName(), option.getPrice(), option.isActive());
    }

    private void requireFreeGroupName(Long itemId, String name, Long exceptGroupId) {
        boolean taken = groupRepository.findByItemIdOrderBySortOrderAsc(itemId).stream()
                .anyMatch(other -> !other.getId().equals(exceptGroupId) && other.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ConflictException("This item already has an option group called " + name);
        }
    }

    private void requireFreeOptionName(Long groupId, String name, Long exceptOptionId) {
        boolean taken = optionRepository.findByGroup(groupId).stream()
                .anyMatch(other -> !other.getId().equals(exceptOptionId) && other.getName().equalsIgnoreCase(name));
        if (taken) {
            throw new ConflictException("This group already has an option called " + name);
        }
    }

    private Item requireItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new ResourceNotFoundException("Item " + itemId + " not found"));
    }

    private ModifierGroup requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Option group " + groupId + " not found"));
    }

    private ModifierOption requireOption(Long optionId) {
        return optionRepository.findById(optionId)
                .orElseThrow(() -> new ResourceNotFoundException("Option " + optionId + " not found"));
    }
}
