package com.counterline.pos.catalog;

import com.counterline.pos.catalog.CatalogDtos.CategoryItemsResponse;
import com.counterline.pos.catalog.CatalogDtos.ItemOptionsResponse;
import com.counterline.pos.catalog.CatalogDtos.TabResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@SecurityRequirement(name = "bearer")
class CatalogController {

    private final CatalogService catalogService;

    CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(summary = "Sell-screen tabs with their category folder grid")
    @GetMapping("/tabs")
    List<TabResponse> getTabs() {
        return catalogService.getTabs();
    }

    @Operation(summary = "Option groups of an item, for the item dialog (e.g. \"CHOOSE 10\" flavours)")
    @GetMapping("/items/{itemId}/options")
    ItemOptionsResponse getItemOptions(@PathVariable Long itemId) {
        return catalogService.getItemOptions(itemId);
    }

    @Operation(summary = "Active items of a category, in display order")
    @GetMapping("/categories/{categoryId}/items")
    CategoryItemsResponse getCategoryItems(@PathVariable Long categoryId) {
        return catalogService.getCategoryItems(categoryId);
    }
}
