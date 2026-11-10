package app.brewflow.menu;

import java.util.List;
import java.util.UUID;

/** Catalog projections. Money is a two-decimal string. */
public final class MenuViews {

    private MenuViews() {
    }

    public record Catalog(long revision, String currency, List<Category> categories, List<Group> modifierGroups) {
    }

    public record Category(UUID id, String title, int sortOrder, boolean active, int revision, List<Item> items) {
    }

    public record Item(UUID id, UUID categoryId, String title, String basePrice, boolean available,
                       boolean lowStockFlag, boolean active, int revision, List<Group> modifierGroups) {
    }

    public record Group(UUID id, String title, int minSelected, int maxSelected, boolean active, int revision,
                        List<Option> options) {
    }

    public record Option(UUID id, UUID groupId, String title, String priceDelta, boolean active, int revision) {
    }
}
