package app.brewflow.menu;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import app.brewflow.menu.MenuService.CategoryPatch;
import app.brewflow.menu.MenuService.GroupPatch;
import app.brewflow.menu.MenuService.ItemCreate;
import app.brewflow.menu.MenuService.ItemPatch;
import app.brewflow.menu.MenuService.OptionPatch;
import app.brewflow.menu.MenuViews.Catalog;
import app.brewflow.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Menu", description = "Catalog for the POS and admin menu management (ADR-011)")
public class MenuController {

    public record CategoryCreateRequest(@NotNull @Size(max = 120) String title, Integer sortOrder) {
    }

    public record CategoryPatchRequest(String title, Integer sortOrder, Boolean active, @NotNull Integer expectedRevision) {
    }

    public record ItemCreateRequest(@NotNull UUID categoryId, @NotNull String title, @NotNull BigDecimal basePrice,
                                    Boolean available, Boolean lowStockFlag) {
    }

    public record ItemPatchRequest(UUID categoryId, String title, BigDecimal basePrice, Boolean available,
                                   Boolean lowStockFlag, Boolean active, @NotNull Integer expectedRevision) {
    }

    public record GroupCreateRequest(@NotNull String title, @NotNull Integer minSelected, @NotNull Integer maxSelected) {
    }

    public record GroupPatchRequest(String title, Integer minSelected, Integer maxSelected, Boolean active,
                                    @NotNull Integer expectedRevision) {
    }

    public record OptionCreateRequest(@NotNull UUID groupId, @NotNull String title, @NotNull BigDecimal priceDelta) {
    }

    public record OptionPatchRequest(String title, BigDecimal priceDelta, Boolean active,
                                     @NotNull Integer expectedRevision) {
    }

    public record ItemGroupsRequest(@NotNull @Size(max = 20) List<@NotNull UUID> groupIds,
                                    @NotNull Integer expectedRevision) {
    }

    private final MenuService menu;

    public MenuController(MenuService menu) {
        this.menu = menu;
    }

    @Operation(summary = "Active catalog and revision (cashier, admin)")
    @GetMapping("/api/v1/menu")
    Catalog activeMenu() {
        return menu.activeCatalog();
    }

    @Operation(summary = "Full catalog including inactive rows (admin)")
    @GetMapping("/api/v1/admin/menu")
    Catalog adminMenu() {
        return menu.adminCatalog();
    }

    @Operation(summary = "Create a category")
    @PostMapping("/api/v1/admin/menu/categories")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createCategory(@Valid @RequestBody CategoryCreateRequest b) {
        return created(menu.createCategory(actor(), b.title(), b.sortOrder()));
    }

    @Operation(summary = "Edit a category; 409 MENU_CHANGED on stale revision")
    @PatchMapping("/api/v1/admin/menu/categories/{id}")
    Catalog patchCategory(@PathVariable UUID id, @Valid @RequestBody CategoryPatchRequest b) {
        menu.patchCategory(actor(), id, new CategoryPatch(b.title(), b.sortOrder(), b.active(), b.expectedRevision()));
        return menu.adminCatalog();
    }

    @Operation(summary = "Create an item; basePrice >= 0")
    @PostMapping("/api/v1/admin/menu/items")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createItem(@Valid @RequestBody ItemCreateRequest b) {
        return created(menu.createItem(actor(),
                new ItemCreate(b.categoryId(), b.title(), b.basePrice(), b.available(), b.lowStockFlag())));
    }

    @Operation(summary = "Edit price, title, availability, category; 409 MENU_CHANGED")
    @PatchMapping("/api/v1/admin/menu/items/{id}")
    Catalog patchItem(@PathVariable UUID id, @Valid @RequestBody ItemPatchRequest b) {
        menu.patchItem(actor(), id, new ItemPatch(b.categoryId(), b.title(), b.basePrice(), b.available(),
                b.lowStockFlag(), b.active(), b.expectedRevision()));
        return menu.adminCatalog();
    }

    @Operation(summary = "Replace the item's modifier groups; 409 MENU_CHANGED")
    @PutMapping("/api/v1/admin/menu/items/{id}/modifier-groups")
    Catalog replaceItemGroups(@PathVariable UUID id, @Valid @RequestBody ItemGroupsRequest b) {
        menu.replaceItemGroups(actor(), id, b.groupIds(), b.expectedRevision());
        return menu.adminCatalog();
    }

    @Operation(summary = "Create a modifier group; 0 <= min <= max")
    @PostMapping("/api/v1/admin/menu/modifier-groups")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createGroup(@Valid @RequestBody GroupCreateRequest b) {
        return created(menu.createGroup(actor(), b.title(), b.minSelected(), b.maxSelected()));
    }

    @Operation(summary = "Edit title, min, max, active")
    @PatchMapping("/api/v1/admin/menu/modifier-groups/{id}")
    Catalog patchGroup(@PathVariable UUID id, @Valid @RequestBody GroupPatchRequest b) {
        menu.patchGroup(actor(), id, new GroupPatch(b.title(), b.minSelected(), b.maxSelected(), b.active(),
                b.expectedRevision()));
        return menu.adminCatalog();
    }

    @Operation(summary = "Create an option; priceDelta >= 0")
    @PostMapping("/api/v1/admin/menu/modifier-options")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, UUID> createOption(@Valid @RequestBody OptionCreateRequest b) {
        return created(menu.createOption(actor(), b.groupId(), b.title(), b.priceDelta()));
    }

    @Operation(summary = "Edit title, delta, active")
    @PatchMapping("/api/v1/admin/menu/modifier-options/{id}")
    Catalog patchOption(@PathVariable UUID id, @Valid @RequestBody OptionPatchRequest b) {
        menu.patchOption(actor(), id, new OptionPatch(b.title(), b.priceDelta(), b.active(), b.expectedRevision()));
        return menu.adminCatalog();
    }

    private static UUID actor() {
        return CurrentUser.get().id();
    }

    private static Map<String, UUID> created(UUID id) {
        return Map.of("id", id);
    }
}
