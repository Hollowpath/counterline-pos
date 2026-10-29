package com.counterline.pos.catalog;

import com.counterline.pos.catalog.CatalogAdminService.AdminCategory;
import com.counterline.pos.catalog.CatalogAdminService.AdminItem;
import com.counterline.pos.catalog.CatalogAdminService.FolderRequest;
import com.counterline.pos.catalog.CatalogAdminService.ItemRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    @Operation(summary = "Add an item at the end of a folder")
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    AdminItem create(@Valid @RequestBody ItemRequest request) {
        return catalogAdmin.create(request);
    }

    @Operation(summary = "Rename an item, change its price, or take it off sale",
            description = "Past sales keep the name and price they were sold at.")
    @PutMapping("/items/{itemId}")
    AdminItem update(@PathVariable Long itemId, @Valid @RequestBody ItemRequest request) {
        return catalogAdmin.update(itemId, request);
    }
}
