package com.counterline.pos.catalog;

import com.counterline.pos.catalog.CatalogAdminService.AdminCategory;
import com.counterline.pos.catalog.CatalogAdminService.AdminItem;
import com.counterline.pos.catalog.CatalogAdminService.AdminTab;
import com.counterline.pos.catalog.CatalogAdminService.FolderRequest;
import com.counterline.pos.catalog.CatalogAdminService.FolderUpdate;
import com.counterline.pos.catalog.CatalogAdminService.OrderRequest;
import com.counterline.pos.catalog.CatalogAdminService.Removal;
import com.counterline.pos.catalog.CatalogAdminService.TabRequest;
import com.counterline.pos.catalog.CatalogAdminService.ItemRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

/** Dashboard, head office only: items and prices for every outlet. */
@RestController
@RequestMapping("/api/v1/dashboard/catalog")
@SecurityRequirement(name = "bearer")
class CatalogAdminController {

    private final CatalogAdminService catalogAdmin;

    CatalogAdminController(CatalogAdminService catalogAdmin) {
        this.catalogAdmin = catalogAdmin;
    }

    @Operation(summary = "Every folder with every item, on sale or not")
    @GetMapping("/items")
    List<AdminCategory> catalogue() {
        return catalogAdmin.catalogue();
    }

    @Operation(summary = "Add a folder to the till grid",
            description = "Placed on the tab of that name (created if new) in the first free cell, row by row.")
    @PostMapping("/folders")
    @ResponseStatus(HttpStatus.CREATED)
    AdminCategory createFolder(@Valid @RequestBody FolderRequest request) {
        return catalogAdmin.createFolder(request);
    }

    @Operation(summary = "Rename a folder, move it to another tab or cell, take it off the till grid, or set whether it counts for promotions")
    @PutMapping("/folders/{folderId}")
    AdminCategory updateFolder(@PathVariable Long folderId, @Valid @RequestBody FolderUpdate request) {
        return catalogAdmin.updateFolder(folderId, request);
    }

    @Operation(summary = "Delete an empty folder",
            description = "Refused (409) while it has items. Past sales keep the folder name they were sold under.")
    @DeleteMapping("/folders/{folderId}")
    ResponseEntity<Void> deleteFolder(@PathVariable Long folderId) {
        catalogAdmin.deleteFolder(folderId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Put a folder's items in the order wanted (list every item id of the folder)")
    @PutMapping("/folders/{folderId}/items/order")
    List<AdminItem> reorderItems(@PathVariable Long folderId, @RequestBody OrderRequest request) {
        return catalogAdmin.reorderItems(folderId, request);
    }

    @Operation(summary = "Every quick-grid tab in till order, with its folder count")
    @GetMapping("/tabs")
    List<AdminTab> tabs() {
        return catalogAdmin.tabs();
    }

    @Operation(summary = "Put the tabs in the order wanted (list every tab id)")
    @PutMapping("/tabs/order")
    List<AdminTab> reorderTabs(@RequestBody OrderRequest request) {
        return catalogAdmin.reorderTabs(request);
    }

    @Operation(summary = "Rename a tab")
    @PutMapping("/tabs/{tabId}")
    AdminTab renameTab(@PathVariable Long tabId, @Valid @RequestBody TabRequest request) {
        return catalogAdmin.renameTab(tabId, request);
    }

    @Operation(summary = "Delete a tab that has no folders")
    @DeleteMapping("/tabs/{tabId}")
    ResponseEntity<Void> deleteTab(@PathVariable Long tabId) {
        catalogAdmin.deleteTab(tabId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add an item at the end of a folder")
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    AdminItem create(@Valid @RequestBody ItemRequest request) {
        return catalogAdmin.create(request);
    }

    @Operation(summary = "Remove an item",
            description = "Deleted if it has never been sold; otherwise taken off sale so past sales, reports and "
                    + "rewards keep referring to it. The outcome says which.")
    @DeleteMapping("/items/{itemId}")
    Removal removeItem(@PathVariable Long itemId) {
        return catalogAdmin.removeItem(itemId);
    }

    @Operation(summary = "Rename an item, change its price, move it to another folder, or take it off sale",
            description = "Past sales keep the name and price they were sold at.")
    @PutMapping("/items/{itemId}")
    AdminItem update(@PathVariable Long itemId, @Valid @RequestBody ItemRequest request) {
        return catalogAdmin.update(itemId, request);
    }
}
