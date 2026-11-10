package app.brewflow.support;

import app.brewflow.support.ApiClient.Resp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Example cafe menu, created through the admin API. Fictitious prices in KGS.
 * Size (exactly 1): S +0.00, M +40.00, L +70.00. Milk (0..1): Oat +45.50, Almond +55.25, Lactose-free +30.00.
 * Syrup (0..2): Vanilla +25.00, Caramel +25.00, Hazelnut +27.50. Extra (0..1): Shot +35.00.
 */
public class MenuFixture {

    public final Map<String, UUID> items = new HashMap<>();
    public final Map<String, UUID> options = new HashMap<>();
    public final Map<String, UUID> groups = new HashMap<>();
    public final Map<String, UUID> categories = new HashMap<>();

    public static MenuFixture create(ApiClient admin) {
        MenuFixture f = new MenuFixture();
        f.categories.put("Coffee", f.id(admin.post("/api/v1/admin/menu/categories", Map.of("title", "Coffee", "sortOrder", 1))));
        f.categories.put("Tea", f.id(admin.post("/api/v1/admin/menu/categories", Map.of("title", "Tea", "sortOrder", 2))));
        f.categories.put("Food", f.id(admin.post("/api/v1/admin/menu/categories", Map.of("title", "Food", "sortOrder", 3))));

        f.item(admin, "Coffee", "Espresso", "120.00");
        f.item(admin, "Coffee", "Americano", "140.00");
        f.item(admin, "Coffee", "Cappuccino", "180.00");
        f.item(admin, "Coffee", "Latte", "190.00");
        f.item(admin, "Coffee", "Flat white", "200.00");
        f.item(admin, "Coffee", "Raf", "230.00");
        f.item(admin, "Tea", "Black tea", "90.00");
        f.item(admin, "Tea", "Matcha latte", "250.00");
        f.item(admin, "Food", "Croissant", "110.00");
        f.item(admin, "Food", "Cheesecake", "260.00");
        f.items.put("Muffin", f.id(admin.post("/api/v1/admin/menu/items", Map.of("categoryId", f.categories.get("Food"),
                "title", "Muffin", "basePrice", "100.00", "available", false))));

        f.group(admin, "Size", 1, 1, Map.of("S", "0.00", "M", "40.00", "L", "70.00"));
        f.group(admin, "Milk", 0, 1, Map.of("Oat", "45.50", "Almond", "55.25", "Lactose-free", "30.00"));
        f.group(admin, "Syrup", 0, 2, Map.of("Vanilla", "25.00", "Caramel", "25.00", "Hazelnut", "27.50"));
        f.group(admin, "Extra", 0, 1, Map.of("Shot", "35.00"));

        f.attach(admin, "Espresso", "Syrup", "Extra");
        f.attach(admin, "Americano", "Milk", "Syrup", "Extra");
        f.attach(admin, "Cappuccino", "Size", "Milk", "Syrup");
        f.attach(admin, "Latte", "Size", "Milk", "Syrup", "Extra");
        f.attach(admin, "Flat white", "Size", "Milk");
        f.attach(admin, "Raf", "Size", "Milk", "Syrup");
        f.attach(admin, "Matcha latte", "Size", "Milk");
        return f;
    }

    public Map<String, Object> line(String item, int quantity, String... optionNames) {
        return Map.of("menuItemId", items.get(item), "quantity", quantity,
                "optionIds", java.util.Arrays.stream(optionNames).map(options::get).toList());
    }

    @SafeVarargs
    public static Map<String, Object> cart(Map<String, Object>... lines) {
        return Map.of("lines", List.of(lines));
    }

    private void item(ApiClient admin, String category, String title, String price) {
        items.put(title, id(admin.post("/api/v1/admin/menu/items",
                Map.of("categoryId", categories.get(category), "title", title, "basePrice", price))));
    }

    private void group(ApiClient admin, String title, int min, int max, Map<String, String> opts) {
        UUID g = id(admin.post("/api/v1/admin/menu/modifier-groups",
                Map.of("title", title, "minSelected", min, "maxSelected", max)));
        groups.put(title, g);
        opts.forEach((name, delta) -> options.put(name, id(admin.post("/api/v1/admin/menu/modifier-options",
                Map.of("groupId", g, "title", name, "priceDelta", delta)))));
    }

    private void attach(ApiClient admin, String item, String... groupNames) {
        int revision = itemRevision(admin, item);
        Resp r = admin.put("/api/v1/admin/menu/items/" + items.get(item) + "/modifier-groups", Map.of(
                "groupIds", java.util.Arrays.stream(groupNames).map(groups::get).toList(), "expectedRevision", revision));
        if (r.status() != 200) {
            throw new IllegalStateException("attach failed: " + r.raw());
        }
    }

    public int itemRevision(ApiClient admin, String item) {
        Resp menu = admin.get("/api/v1/admin/menu");
        for (var c : menu.body().path("categories")) {
            for (var i : c.path("items")) {
                if (i.path("id").asString().equals(items.get(item).toString())) {
                    return i.path("revision").asInt();
                }
            }
        }
        throw new IllegalStateException("item not found: " + item);
    }

    private UUID id(Resp r) {
        if (r.status() != 201) {
            throw new IllegalStateException("create failed: " + r.status() + " " + r.raw());
        }
        return UUID.fromString(r.text("id"));
    }
}
