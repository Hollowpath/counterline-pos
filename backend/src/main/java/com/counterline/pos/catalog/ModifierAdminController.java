package com.counterline.pos.catalog;

import com.counterline.pos.catalog.ModifierAdminService.AdminGroup;
import com.counterline.pos.catalog.ModifierAdminService.AdminOption;
import com.counterline.pos.catalog.ModifierAdminService.GroupRequest;
import com.counterline.pos.catalog.ModifierAdminService.OptionRequest;
import com.counterline.pos.catalog.ModifierAdminService.OrderRequest;
import com.counterline.pos.catalog.ModifierAdminService.Removal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Dashboard, head office only: an item's option groups and options. */
@RestController
@RequestMapping("/api/v1/dashboard/catalog")
@SecurityRequirement(name = "bearer")
class ModifierAdminController {

    private final ModifierAdminService modifiers;

    ModifierAdminController(ModifierAdminService modifiers) {
        this.modifiers = modifiers;
    }

    @Operation(summary = "An item's option groups with every option, active or not")
    @GetMapping("/items/{itemId}/modifier-groups")
    List<AdminGroup> groups(@PathVariable Long itemId) {
        return modifiers.groups(itemId);
    }

    @Operation(summary = "Add an option group at the end of an item's groups")
    @PostMapping("/items/{itemId}/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    AdminGroup createGroup(@PathVariable Long itemId, @Valid @RequestBody GroupRequest request) {
        return modifiers.createGroup(itemId, request);
    }

    @Operation(summary = "Put an item's option groups in the order wanted (list every group id)")
    @PutMapping("/items/{itemId}/modifier-groups/order")
    List<AdminGroup> reorderGroups(@PathVariable Long itemId, @RequestBody OrderRequest request) {
        return modifiers.reorderGroups(itemId, request);
    }

    @Operation(summary = "Rename a group or change how many picks it needs")
    @PutMapping("/modifier-groups/{groupId}")
    AdminGroup updateGroup(@PathVariable Long groupId, @Valid @RequestBody GroupRequest request) {
        return modifiers.updateGroup(groupId, request);
    }

    @Operation(summary = "Remove a group",
            description = "Deleted if none of its options was ever sold; otherwise all its options are deactivated "
                    + "and the group disappears from the till, keeping past sales intact.")
    @DeleteMapping("/modifier-groups/{groupId}")
    Removal removeGroup(@PathVariable Long groupId) {
        return modifiers.removeGroup(groupId);
    }

    @Operation(summary = "Add an option at the end of a group")
    @PostMapping("/modifier-groups/{groupId}/options")
    @ResponseStatus(HttpStatus.CREATED)
    AdminOption createOption(@PathVariable Long groupId, @Valid @RequestBody OptionRequest request) {
        return modifiers.createOption(groupId, request);
    }

    @Operation(summary = "Put a group's options in the order wanted (list every option id)")
    @PutMapping("/modifier-groups/{groupId}/options/order")
    List<AdminOption> reorderOptions(@PathVariable Long groupId, @RequestBody OrderRequest request) {
        return modifiers.reorderOptions(groupId, request);
    }

    @Operation(summary = "Rename an option, change its price, or take it off sale")
    @PutMapping("/modifier-options/{optionId}")
    AdminOption updateOption(@PathVariable Long optionId, @Valid @RequestBody OptionRequest request) {
        return modifiers.updateOption(optionId, request);
    }

    @Operation(summary = "Remove an option",
            description = "Deleted if it was never sold; otherwise deactivated, keeping past sales intact.")
    @DeleteMapping("/modifier-options/{optionId}")
    Removal removeOption(@PathVariable Long optionId) {
        return modifiers.removeOption(optionId);
    }
}
