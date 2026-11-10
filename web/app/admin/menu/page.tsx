"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell, Notice } from "@/components/AdminShell";
import { MONEY, errorText, isStale, type AdminCatalog, type AdminCategory, type AdminGroup, type AdminItem, type AdminOption } from "@/lib/admin";
import { api } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

type Run = (action: () => Promise<AdminCatalog | unknown>, okText?: string) => Promise<boolean>;

export default function MenuAdminPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [catalog, setCatalog] = useState<AdminCatalog | null>(null);
  const [notice, setNotice] = useState<{ kind: "error" | "ok" | "warn"; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api.get<AdminCatalog>("/admin/menu").then(setCatalog), []);

  useEffect(() => {
    if (me) load().catch((e) => setNotice({ kind: "error", text: errorText(e, t("offline")) }));
  }, [me, load, t]);

  // every write sends the revision it saw; a stale one reloads the menu (409 MENU_CHANGED)
  const run: Run = async (action, okText) => {
    setBusy(true);
    setNotice(null);
    try {
      const result = await action();
      if (result && typeof result === "object" && "categories" in result) setCatalog(result as AdminCatalog);
      else await load();
      if (okText) setNotice({ kind: "ok", text: okText });
      return true;
    } catch (e) {
      if (isStale(e)) {
        await load().catch(() => undefined);
        setNotice({ kind: "warn", text: tr("Меню изменилось, данные обновлены. Повторите действие.", "The menu changed and was reloaded. Repeat the action.") });
      } else {
        setNotice({ kind: "error", text: errorText(e, t("offline")) });
      }
      return false;
    } finally {
      setBusy(false);
    }
  };

  const groups = catalog?.modifierGroups ?? [];

  return (
    <AdminShell me={me} title={tr("Меню", "Menu")}>
      <div className="flex flex-col gap-6">
        {notice && <Notice kind={notice.kind}>{notice.text}</Notice>}
        <p className="text-sm text-stone-600">
          {tr("Позиции не удаляются, а отключаются: старые заказы хранят свои цены. Изменение цены влияет только на новые расчёты.",
            "Rows are disabled, never deleted: past orders keep their prices. A price change affects new quotes only.")}
        </p>
        {catalog && (
          <>
            <CategoriesSection catalog={catalog} run={run} busy={busy} />
            {catalog.categories.map((c) => (
              <ItemsSection key={c.id} category={c} catalog={catalog} groups={groups} run={run} busy={busy} />
            ))}
            <GroupsSection groups={groups} run={run} busy={busy} />
          </>
        )}
      </div>
    </AdminShell>
  );
}

function Toggle({ on, label, disabled, onChange }: { on: boolean; label: string; disabled: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex min-h-11 items-center gap-2 text-sm">
      <input type="checkbox" className="size-5" checked={on} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

// ---------------------------------------------------------------- categories

function CategoriesSection({ catalog, run, busy }: { catalog: AdminCatalog; run: Run; busy: boolean }) {
  const tr = useTr();
  const [title, setTitle] = useState("");

  async function add(e: FormEvent) {
    e.preventDefault();
    const sortOrder = catalog.categories.length + 1;
    if (await run(() => api.post("/admin/menu/categories", { title: title.trim(), sortOrder }), tr("Категория добавлена.", "Category added."))) {
      setTitle("");
    }
  }

  return (
    <section className="card flex flex-col gap-3">
      <h2 className="text-lg font-semibold">{tr("Категории", "Categories")}</h2>
      <ul className="flex flex-col gap-2">
        {catalog.categories.map((c) => (
          <CategoryRow key={`${c.id}:${c.revision}`} category={c} run={run} busy={busy} />
        ))}
      </ul>
      <form onSubmit={add} className="flex flex-wrap gap-2">
        <input className="field max-w-xs" placeholder={tr("Новая категория", "New category")} value={title}
               onChange={(e) => setTitle(e.target.value)} maxLength={120} required />
        <button className="btn-primary" disabled={busy || !title.trim()}>{tr("Добавить", "Add")}</button>
      </form>
    </section>
  );
}

function CategoryRow({ category, run, busy }: { category: AdminCategory; run: Run; busy: boolean }) {
  const tr = useTr();
  const [title, setTitle] = useState(category.title);
  const [sort, setSort] = useState(String(category.sortOrder));
  const patch = (body: object) => run(() => api.patch(`/admin/menu/categories/${category.id}`, { ...body, expectedRevision: category.revision }));
  const dirty = title.trim() !== category.title || sort !== String(category.sortOrder);
  return (
    <li className="flex flex-wrap items-center gap-2">
      <input className="field max-w-xs" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />
      <input className="field w-20" inputMode="numeric" value={sort} onChange={(e) => setSort(e.target.value)}
             aria-label={tr("Порядок", "Order")} />
      <button type="button" className="btn-secondary" disabled={busy || !dirty || !title.trim() || !/^-?\d+$/.test(sort)}
              onClick={() => void patch({ title: title.trim(), sortOrder: Number(sort) })}>
        {tr("Сохранить", "Save")}
      </button>
      <Toggle on={category.active} disabled={busy} label={tr("активна", "active")} onChange={(v) => void patch({ active: v })} />
      <span className="text-xs text-stone-400">{category.items.length} {tr("поз.", "items")}</span>
    </li>
  );
}

// ---------------------------------------------------------------- items

function ItemsSection({ category, catalog, groups, run, busy }: {
  category: AdminCategory; catalog: AdminCatalog; groups: AdminGroup[]; run: Run; busy: boolean;
}) {
  const tr = useTr();
  const [title, setTitle] = useState("");
  const [price, setPrice] = useState("");

  async function add(e: FormEvent) {
    e.preventDefault();
    const ok = await run(() => api.post("/admin/menu/items", { categoryId: category.id, title: title.trim(), basePrice: price.trim() }),
      tr("Позиция добавлена.", "Item added."));
    if (ok) {
      setTitle("");
      setPrice("");
    }
  }

  return (
    <section className={`card flex flex-col gap-3 ${category.active ? "" : "opacity-60"}`}>
      <h2 className="text-lg font-semibold">
        {category.title} {!category.active && <span className="text-sm text-rose-700">({tr("отключена", "disabled")})</span>}
      </h2>
      <ul className="flex flex-col divide-y divide-stone-100">
        {category.items.map((item) => (
          <ItemRow key={`${item.id}:${item.revision}`} item={item} catalog={catalog} groups={groups} run={run} busy={busy} />
        ))}
      </ul>
      <form onSubmit={add} className="flex flex-wrap gap-2">
        <input className="field max-w-xs" placeholder={tr("Новая позиция", "New item")} value={title}
               onChange={(e) => setTitle(e.target.value)} maxLength={120} required />
        <input className="field w-32 font-mono" inputMode="decimal" placeholder="0.00" value={price} onChange={(e) => setPrice(e.target.value)} />
        <button className="btn-primary" disabled={busy || !title.trim() || !MONEY.test(price.trim())}>{tr("Добавить", "Add")}</button>
      </form>
    </section>
  );
}

function ItemRow({ item, catalog, groups, run, busy }: {
  item: AdminItem; catalog: AdminCatalog; groups: AdminGroup[]; run: Run; busy: boolean;
}) {
  const tr = useTr();
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(item.title);
  const [price, setPrice] = useState(item.basePrice);
  const [categoryId, setCategoryId] = useState(item.categoryId);
  const [groupIds, setGroupIds] = useState(item.modifierGroups.map((g) => g.id));
  const patch = (body: object) => run(() => api.patch(`/admin/menu/items/${item.id}`, { ...body, expectedRevision: item.revision }));

  async function save() {
    const changedGroups = groupIds.join() !== item.modifierGroups.map((g) => g.id).join();
    const ok = await run(async () => {
      let revision = item.revision;
      if (title.trim() !== item.title || price !== item.basePrice || categoryId !== item.categoryId) {
        const updated = await api.patch<AdminCatalog>(`/admin/menu/items/${item.id}`, {
          title: title.trim(), basePrice: price.trim(), categoryId, expectedRevision: revision,
        });
        revision = updated.categories.flatMap((c) => c.items).find((i) => i.id === item.id)?.revision ?? revision + 1;
        if (!changedGroups) return updated;
      }
      if (changedGroups) {
        return api.put<AdminCatalog>(`/admin/menu/items/${item.id}/modifier-groups`, { groupIds, expectedRevision: revision });
      }
      return undefined;
    }, tr("Позиция сохранена.", "Item saved."));
    if (ok) setEditing(false);
  }

  return (
    <li className={`flex flex-col gap-2 py-2 ${item.active ? "" : "opacity-60"}`}>
      <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
        <span className="min-w-40 font-medium break-words">{item.title}</span>
        <span className="w-24 font-mono">{item.basePrice}</span>
        <Toggle on={item.available} disabled={busy} label={tr("в наличии", "available")} onChange={(v) => void patch({ available: v })} />
        <Toggle on={item.lowStockFlag} disabled={busy} label={tr("мало", "low stock")} onChange={(v) => void patch({ lowStockFlag: v })} />
        <Toggle on={item.active} disabled={busy} label={tr("активна", "active")} onChange={(v) => void patch({ active: v })} />
        <span className="text-xs text-stone-500">{item.modifierGroups.map((g) => g.title).join(", ")}</span>
        <button type="button" className="btn-secondary ml-auto" onClick={() => setEditing((v) => !v)}>
          {editing ? tr("Скрыть", "Hide") : tr("Изменить", "Edit")}
        </button>
      </div>
      {editing && (
        <div className="flex flex-col gap-2 rounded-lg bg-stone-50 p-3">
          <div className="flex flex-wrap gap-2">
            <input className="field max-w-xs" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />
            <input className="field w-32 font-mono" inputMode="decimal" value={price} onChange={(e) => setPrice(e.target.value)} />
            <select className="field max-w-xs" value={categoryId} onChange={(e) => setCategoryId(e.target.value)}>
              {catalog.categories.map((c) => <option key={c.id} value={c.id}>{c.title}</option>)}
            </select>
          </div>
          <fieldset className="flex flex-wrap gap-x-4">
            <legend className="text-sm font-medium">{tr("Группы модификаторов", "Modifier groups")}</legend>
            {groups.map((g) => (
              <Toggle key={g.id} on={groupIds.includes(g.id)} disabled={busy} label={g.title}
                      onChange={(v) => setGroupIds((ids) => (v ? [...ids, g.id] : ids.filter((x) => x !== g.id)))} />
            ))}
          </fieldset>
          <button type="button" className="btn-primary self-start" disabled={busy || !title.trim() || !MONEY.test(price.trim())}
                  onClick={() => void save()}>
            {tr("Сохранить", "Save")}
          </button>
        </div>
      )}
    </li>
  );
}

// ---------------------------------------------------------------- modifier groups and options

function GroupsSection({ groups, run, busy }: { groups: AdminGroup[]; run: Run; busy: boolean }) {
  const tr = useTr();
  const [title, setTitle] = useState("");
  const [min, setMin] = useState("0");
  const [max, setMax] = useState("1");
  const validLimits = /^\d+$/.test(min) && /^\d+$/.test(max) && Number(min) <= Number(max) && Number(max) <= 20;

  async function add(e: FormEvent) {
    e.preventDefault();
    const ok = await run(() => api.post("/admin/menu/modifier-groups", { title: title.trim(), minSelected: Number(min), maxSelected: Number(max) }),
      tr("Группа добавлена.", "Group added."));
    if (ok) setTitle("");
  }

  return (
    <section className="card flex flex-col gap-4">
      <h2 className="text-lg font-semibold">{tr("Модификаторы", "Modifiers")}</h2>
      <p className="text-sm text-stone-600">
        {tr("«от–до» — сколько вариантов гость обязан и может выбрать. Доплата не может быть отрицательной.",
          "“min–max” is how many choices are required and allowed. A surcharge cannot be negative.")}
      </p>
      {groups.map((g) => <GroupBlock key={`${g.id}:${g.revision}`} group={g} run={run} busy={busy} />)}
      <form onSubmit={add} className="flex flex-wrap items-end gap-2">
        <input className="field max-w-xs" placeholder={tr("Новая группа", "New group")} value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} required />
        <input className="field w-20" inputMode="numeric" aria-label="min" value={min} onChange={(e) => setMin(e.target.value)} />
        <input className="field w-20" inputMode="numeric" aria-label="max" value={max} onChange={(e) => setMax(e.target.value)} />
        <button className="btn-primary" disabled={busy || !title.trim() || !validLimits}>{tr("Добавить", "Add")}</button>
      </form>
    </section>
  );
}

function GroupBlock({ group, run, busy }: { group: AdminGroup; run: Run; busy: boolean }) {
  const tr = useTr();
  const [title, setTitle] = useState(group.title);
  const [min, setMin] = useState(String(group.minSelected));
  const [max, setMax] = useState(String(group.maxSelected));
  const [optTitle, setOptTitle] = useState("");
  const [optDelta, setOptDelta] = useState("0.00");
  const patch = (body: object) => run(() => api.patch(`/admin/menu/modifier-groups/${group.id}`, { ...body, expectedRevision: group.revision }));
  const validLimits = /^\d+$/.test(min) && /^\d+$/.test(max) && Number(min) <= Number(max) && Number(max) <= 20;
  const dirty = title.trim() !== group.title || min !== String(group.minSelected) || max !== String(group.maxSelected);

  async function addOption(e: FormEvent) {
    e.preventDefault();
    const ok = await run(() => api.post("/admin/menu/modifier-options", { groupId: group.id, title: optTitle.trim(), priceDelta: optDelta.trim() }),
      tr("Вариант добавлен.", "Option added."));
    if (ok) {
      setOptTitle("");
      setOptDelta("0.00");
    }
  }

  return (
    <div className={`rounded-lg border border-stone-200 p-3 ${group.active ? "" : "opacity-60"}`}>
      <div className="flex flex-wrap items-center gap-2">
        <input className="field max-w-xs font-semibold" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />
        <span className="text-sm">{tr("от", "min")}</span>
        <input className="field w-16" inputMode="numeric" value={min} onChange={(e) => setMin(e.target.value)} />
        <span className="text-sm">{tr("до", "max")}</span>
        <input className="field w-16" inputMode="numeric" value={max} onChange={(e) => setMax(e.target.value)} />
        <button type="button" className="btn-secondary" disabled={busy || !dirty || !validLimits || !title.trim()}
                onClick={() => void patch({ title: title.trim(), minSelected: Number(min), maxSelected: Number(max) })}>
          {tr("Сохранить", "Save")}
        </button>
        <Toggle on={group.active} disabled={busy} label={tr("активна", "active")} onChange={(v) => void patch({ active: v })} />
      </div>
      <ul className="mt-2 flex flex-col gap-1 pl-2">
        {group.options.map((o) => <OptionRow key={`${o.id}:${o.revision}`} option={o} run={run} busy={busy} />)}
      </ul>
      <form onSubmit={addOption} className="mt-2 flex flex-wrap gap-2 pl-2">
        <input className="field max-w-xs" placeholder={tr("Новый вариант", "New option")} value={optTitle} onChange={(e) => setOptTitle(e.target.value)} maxLength={120} required />
        <input className="field w-28 font-mono" inputMode="decimal" value={optDelta} onChange={(e) => setOptDelta(e.target.value)} />
        <button className="btn-secondary" disabled={busy || !optTitle.trim() || !MONEY.test(optDelta.trim())}>{tr("Добавить", "Add")}</button>
      </form>
    </div>
  );
}

function OptionRow({ option, run, busy }: { option: AdminOption; run: Run; busy: boolean }) {
  const tr = useTr();
  const [title, setTitle] = useState(option.title);
  const [delta, setDelta] = useState(option.priceDelta);
  const patch = (body: object) => run(() => api.patch(`/admin/menu/modifier-options/${option.id}`, { ...body, expectedRevision: option.revision }));
  const dirty = title.trim() !== option.title || delta !== option.priceDelta;
  return (
    <li className={`flex flex-wrap items-center gap-2 ${option.active ? "" : "opacity-60"}`}>
      <input className="field max-w-xs" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={120} />
      <span className="text-sm">+</span>
      <input className="field w-28 font-mono" inputMode="decimal" value={delta} onChange={(e) => setDelta(e.target.value)} />
      <button type="button" className="btn-secondary" disabled={busy || !dirty || !title.trim() || !MONEY.test(delta.trim())}
              onClick={() => void patch({ title: title.trim(), priceDelta: delta.trim() })}>
        {tr("Сохранить", "Save")}
      </button>
      <Toggle on={option.active} disabled={busy} label={tr("активен", "active")} onChange={(v) => void patch({ active: v })} />
    </li>
  );
}
