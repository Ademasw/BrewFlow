package app.brewflow.menu;

import app.brewflow.audit.AuditService;
import app.brewflow.common.ApiException;
import app.brewflow.common.BrewFlowProperties;
import app.brewflow.common.ErrorCode;
import app.brewflow.common.Money;
import app.brewflow.menu.MenuViews.Catalog;
import app.brewflow.menu.MenuViews.Category;
import app.brewflow.menu.MenuViews.Group;
import app.brewflow.menu.MenuViews.Item;
import app.brewflow.menu.MenuViews.Option;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin-managed categories, items, modifier groups and options (ADR-011). Rows are soft-disabled, never deleted,
 * so past order snapshots stay valid. Every write checks expectedRevision and returns 409 MENU_CHANGED when stale.
 */
@Service
public class MenuService {

    public static final int MAX_GROUP_SELECTION = 20;

    public record CategoryPatch(String title, Integer sortOrder, Boolean active, int expectedRevision) {
    }

    public record ItemCreate(UUID categoryId, String title, BigDecimal basePrice, Boolean available, Boolean lowStockFlag) {
    }

    public record ItemPatch(UUID categoryId, String title, BigDecimal basePrice, Boolean available, Boolean lowStockFlag,
                            Boolean active, int expectedRevision) {
    }

    public record GroupPatch(String title, Integer minSelected, Integer maxSelected, Boolean active, int expectedRevision) {
    }

    public record OptionPatch(String title, BigDecimal priceDelta, Boolean active, int expectedRevision) {
    }

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final BrewFlowProperties props;

    public MenuService(JdbcClient jdbc, AuditService audit, BrewFlowProperties props) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.props = props;
    }

    // ---------------------------------------------------------------- reads

    /** GET /menu: active categories and items, attached active groups with active options. */
    @Transactional(readOnly = true)
    public Catalog activeCatalog() {
        return catalog(true);
    }

    /** Admin view, inactive rows included. */
    @Transactional(readOnly = true)
    public Catalog adminCatalog() {
        return catalog(false);
    }

    private Catalog catalog(boolean activeOnly) {
        long revision = jdbc.sql("select revision from menu_catalog").query(Long.class).single();

        Map<UUID, List<Option>> optionsByGroup = jdbc.sql("""
                        select id, group_id, title, price_delta, active, revision from modifier_option
                        order by title, id""")
                .query((rs, n) -> new Option(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                        rs.getString("title"), Money.format(rs.getBigDecimal("price_delta")), rs.getBoolean("active"),
                        rs.getInt("revision")))
                .list().stream()
                .filter(o -> !activeOnly || o.active())
                .collect(Collectors.groupingBy(Option::groupId, LinkedHashMap::new, Collectors.toList()));

        Map<UUID, Group> groups = new LinkedHashMap<>();
        jdbc.sql("select id, title, min_selected, max_selected, active, revision from modifier_group order by title, id")
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Group(id, rs.getString("title"), rs.getInt("min_selected"), rs.getInt("max_selected"),
                            rs.getBoolean("active"), rs.getInt("revision"), optionsByGroup.getOrDefault(id, List.of()));
                })
                .list().stream()
                .filter(g -> !activeOnly || g.active())
                .forEach(g -> groups.put(g.id(), g));

        Map<UUID, List<Group>> groupsByItem = new LinkedHashMap<>();
        jdbc.sql("select item_id, group_id from menu_item_modifier_group order by sort_order, group_id")
                .query((rs, n) -> Map.entry(rs.getObject("item_id", UUID.class), rs.getObject("group_id", UUID.class)))
                .list()
                .forEach(link -> {
                    Group g = groups.get(link.getValue());
                    if (g != null) {
                        groupsByItem.computeIfAbsent(link.getKey(), k -> new ArrayList<>()).add(g);
                    }
                });

        Map<UUID, List<Item>> itemsByCategory = jdbc.sql("""
                        select id, category_id, title, base_price, available, low_stock_flag, active, revision
                        from menu_item order by title, id""")
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Item(id, rs.getObject("category_id", UUID.class), rs.getString("title"),
                            Money.format(rs.getBigDecimal("base_price")), rs.getBoolean("available"),
                            rs.getBoolean("low_stock_flag"), rs.getBoolean("active"), rs.getInt("revision"),
                            groupsByItem.getOrDefault(id, List.of()));
                })
                .list().stream()
                .filter(i -> !activeOnly || i.active())
                .collect(Collectors.groupingBy(Item::categoryId, LinkedHashMap::new, Collectors.toList()));

        List<Category> categories = jdbc.sql("select id, title, sort_order, active, revision from category order by sort_order, title, id")
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Category(id, rs.getString("title"), rs.getInt("sort_order"), rs.getBoolean("active"),
                            rs.getInt("revision"), itemsByCategory.getOrDefault(id, List.of()));
                })
                .list().stream()
                .filter(c -> !activeOnly || c.active())
                .toList();

        return new Catalog(revision, props.currency(), categories, activeOnly ? List.of() : List.copyOf(groups.values()));
    }

    // ---------------------------------------------------------------- categories

    @Transactional
    public UUID createCategory(UUID actor, String title, Integer sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into category (id, title, sort_order) values (:id, :title, :sort)")
                .param("id", id).param("title", requireTitle(title)).param("sort", sortOrder == null ? 0 : sortOrder)
                .update();
        changed(actor, "MENU_CATEGORY_CREATED", "category", id);
        return id;
    }

    @Transactional
    public void patchCategory(UUID actor, UUID id, CategoryPatch p) {
        lockAndCheck("category", id, p.expectedRevision());
        jdbc.sql("""
                        update category set title = coalesce(:title, title), sort_order = coalesce(:sort, sort_order),
                            active = coalesce(:active, active), revision = revision + 1
                        where id = :id""")
                .param("id", id).param("title", p.title() == null ? null : requireTitle(p.title()))
                .param("sort", p.sortOrder()).param("active", p.active())
                .update();
        changed(actor, "MENU_CATEGORY_UPDATED", "category", id);
    }

    // ---------------------------------------------------------------- items

    @Transactional
    public UUID createItem(UUID actor, ItemCreate c) {
        requireCategory(c.categoryId());
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        insert into menu_item (id, category_id, title, base_price, available, low_stock_flag)
                        values (:id, :category, :title, :price, :available, :lowStock)""")
                .param("id", id).param("category", c.categoryId()).param("title", requireTitle(c.title()))
                .param("price", Money.requireNonNegative("basePrice", c.basePrice()))
                .param("available", c.available() == null || c.available())
                .param("lowStock", c.lowStockFlag() != null && c.lowStockFlag())
                .update();
        changed(actor, "MENU_ITEM_CREATED", "menu_item", id);
        return id;
    }

    @Transactional
    public void patchItem(UUID actor, UUID id, ItemPatch p) {
        lockAndCheck("menu_item", id, p.expectedRevision());
        if (p.categoryId() != null) {
            requireCategory(p.categoryId());
        }
        jdbc.sql("""
                        update menu_item set category_id = coalesce(:category, category_id), title = coalesce(:title, title),
                            base_price = coalesce(:price, base_price), available = coalesce(:available, available),
                            low_stock_flag = coalesce(:lowStock, low_stock_flag), active = coalesce(:active, active),
                            revision = revision + 1
                        where id = :id""")
                .param("id", id).param("category", p.categoryId())
                .param("title", p.title() == null ? null : requireTitle(p.title()))
                .param("price", p.basePrice() == null ? null : Money.requireNonNegative("basePrice", p.basePrice()))
                .param("available", p.available()).param("lowStock", p.lowStockFlag()).param("active", p.active())
                .update();
        changed(actor, "MENU_ITEM_UPDATED", "menu_item", id);
    }

    /** PUT /admin/menu/items/{id}/modifier-groups: replace the attached groups; stale item revision is 409. */
    @Transactional
    public void replaceItemGroups(UUID actor, UUID itemId, List<UUID> groupIds, int expectedRevision) {
        lockAndCheck("menu_item", itemId, expectedRevision);
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(groupIds));
        if (distinct.size() != groupIds.size()) {
            throw ApiException.invalid("groupIds", "groupIds contains duplicates.");
        }
        if (!distinct.isEmpty()) {
            long known = jdbc.sql("select count(*) from modifier_group where id in (:ids)")
                    .param("ids", distinct).query(Long.class).single();
            if (known != distinct.size()) {
                throw ApiException.invalid("groupIds", "Unknown modifier group.");
            }
        }
        jdbc.sql("delete from menu_item_modifier_group where item_id = :id").param("id", itemId).update();
        for (int i = 0; i < distinct.size(); i++) {
            jdbc.sql("insert into menu_item_modifier_group (item_id, group_id, sort_order) values (:item, :group, :sort)")
                    .param("item", itemId).param("group", distinct.get(i)).param("sort", i).update();
        }
        jdbc.sql("update menu_item set revision = revision + 1 where id = :id").param("id", itemId).update();
        changed(actor, "MENU_ITEM_GROUPS_REPLACED", "menu_item", itemId);
    }

    // ---------------------------------------------------------------- modifier groups and options

    @Transactional
    public UUID createGroup(UUID actor, String title, Integer min, Integer max) {
        checkSelection(min, max);
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into modifier_group (id, title, min_selected, max_selected) values (:id, :title, :min, :max)")
                .param("id", id).param("title", requireTitle(title)).param("min", min).param("max", max)
                .update();
        changed(actor, "MENU_GROUP_CREATED", "modifier_group", id);
        return id;
    }

    @Transactional
    public void patchGroup(UUID actor, UUID id, GroupPatch p) {
        lockAndCheck("modifier_group", id, p.expectedRevision());
        var current = jdbc.sql("select min_selected, max_selected from modifier_group where id = :id")
                .param("id", id).query((rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}).single();
        checkSelection(p.minSelected() == null ? current[0] : p.minSelected(),
                p.maxSelected() == null ? current[1] : p.maxSelected());
        jdbc.sql("""
                        update modifier_group set title = coalesce(:title, title), min_selected = coalesce(:min, min_selected),
                            max_selected = coalesce(:max, max_selected), active = coalesce(:active, active),
                            revision = revision + 1
                        where id = :id""")
                .param("id", id).param("title", p.title() == null ? null : requireTitle(p.title()))
                .param("min", p.minSelected()).param("max", p.maxSelected()).param("active", p.active())
                .update();
        changed(actor, "MENU_GROUP_UPDATED", "modifier_group", id);
    }

    @Transactional
    public UUID createOption(UUID actor, UUID groupId, String title, BigDecimal priceDelta) {
        boolean groupExists = jdbc.sql("select exists (select 1 from modifier_group where id = :id)")
                .param("id", groupId).query(Boolean.class).single();
        if (!groupExists) {
            throw ApiException.invalid("groupId", "Unknown modifier group.");
        }
        UUID id = UUID.randomUUID();
        jdbc.sql("insert into modifier_option (id, group_id, title, price_delta) values (:id, :group, :title, :delta)")
                .param("id", id).param("group", groupId).param("title", requireTitle(title))
                .param("delta", Money.requireNonNegative("priceDelta", priceDelta))
                .update();
        changed(actor, "MENU_OPTION_CREATED", "modifier_option", id);
        return id;
    }

    @Transactional
    public void patchOption(UUID actor, UUID id, OptionPatch p) {
        lockAndCheck("modifier_option", id, p.expectedRevision());
        jdbc.sql("""
                        update modifier_option set title = coalesce(:title, title), price_delta = coalesce(:delta, price_delta),
                            active = coalesce(:active, active), revision = revision + 1
                        where id = :id""")
                .param("id", id).param("title", p.title() == null ? null : requireTitle(p.title()))
                .param("delta", p.priceDelta() == null ? null : Money.requireNonNegative("priceDelta", p.priceDelta()))
                .param("active", p.active())
                .update();
        changed(actor, "MENU_OPTION_UPDATED", "modifier_option", id);
    }

    // ---------------------------------------------------------------- quote support

    /** A quotable item: active, available, in an active category. */
    public record QuotableItem(UUID id, String title, BigDecimal basePrice) {
    }

    public record AttachedGroup(UUID id, String title, int minSelected, int maxSelected) {
    }

    public record ActiveOption(UUID id, UUID groupId, String title, BigDecimal priceDelta) {
    }

    public Optional<QuotableItem> quotableItem(UUID itemId) {
        return jdbc.sql("""
                        select i.id, i.title, i.base_price from menu_item i join category c on c.id = i.category_id
                        where i.id = :id and i.active and i.available and c.active""")
                .param("id", itemId)
                .query((rs, n) -> new QuotableItem(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getBigDecimal("base_price")))
                .optional();
    }

    public List<AttachedGroup> activeGroupsOf(UUID itemId) {
        return jdbc.sql("""
                        select g.id, g.title, g.min_selected, g.max_selected
                        from menu_item_modifier_group l join modifier_group g on g.id = l.group_id
                        where l.item_id = :id and g.active order by l.sort_order""")
                .param("id", itemId)
                .query((rs, n) -> new AttachedGroup(rs.getObject("id", UUID.class), rs.getString("title"),
                        rs.getInt("min_selected"), rs.getInt("max_selected")))
                .list();
    }

    public Map<UUID, ActiveOption> activeOptions(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("select id, group_id, title, price_delta from modifier_option where active and id in (:ids)")
                .param("ids", List.copyOf(ids))
                .query((rs, n) -> new ActiveOption(rs.getObject("id", UUID.class), rs.getObject("group_id", UUID.class),
                        rs.getString("title"), rs.getBigDecimal("price_delta")))
                .list().stream()
                .collect(Collectors.toMap(ActiveOption::id, o -> o));
    }

    // ---------------------------------------------------------------- helpers

    private void lockAndCheck(String table, UUID id, int expectedRevision) {
        Integer revision = jdbc.sql("select revision from " + table + " where id = :id for update")
                .param("id", id).query(Integer.class).optional().orElseThrow(ApiException::notFound);
        if (revision != expectedRevision) {
            throw new ApiException(ErrorCode.MENU_CHANGED, "The menu changed. Reload and try again.",
                    Map.of("currentRevision", revision));
        }
    }

    private void requireCategory(UUID categoryId) {
        boolean exists = jdbc.sql("select exists (select 1 from category where id = :id)")
                .param("id", categoryId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.invalid("categoryId", "Unknown category.");
        }
    }

    private static String requireTitle(String title) {
        if (title == null || title.isBlank() || title.trim().length() > 120) {
            throw ApiException.invalid("title", "title needs 1 to 120 characters.");
        }
        return title.trim();
    }

    private static void checkSelection(Integer min, Integer max) {
        if (min == null || max == null || min < 0 || min > max || max > MAX_GROUP_SELECTION) {
            throw ApiException.invalid("minSelected", "Selection limits need 0 <= minSelected <= maxSelected <= 20.");
        }
    }

    private void changed(UUID actor, String event, String entityType, UUID id) {
        jdbc.sql("update menu_catalog set revision = revision + 1").update();
        audit.record(actor, event, entityType, id, Map.of());
    }
}
