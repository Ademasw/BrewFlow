"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { ClientOnly } from "@/components/ClientOnly";
import { Shell } from "@/components/Shell";
import { api, ApiError, NoResponseError, type Catalog, type MenuItem, type Quote } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { clearQuote, loadPending, saveQuote, useMe } from "@/lib/session";

type CartLine = { uid: string; item: MenuItem; quantity: number; optionIds: string[] };

function cents(amount: string): number {
  const [whole, frac = "00"] = amount.split(".");
  return Number(whole) * 100 + Number(frac.padEnd(2, "0").slice(0, 2));
}

function money(c: number): string {
  return `${Math.floor(c / 100)}.${String(c % 100).padStart(2, "0")}`;
}

function linePreview(line: CartLine): number {
  const options = line.item.modifierGroups.flatMap((g) => g.options);
  const deltas = line.optionIds.reduce((sum, id) => sum + cents(options.find((o) => o.id === id)?.priceDelta ?? "0"), 0);
  return line.quantity * (cents(line.item.basePrice) + deltas);
}

export default function PosPage() {
  return (
    <ClientOnly>
      <PosPageScreen />
    </ClientOnly>
  );
}

function PosPageScreen() {
  const t = useT();
  const router = useRouter();
  const me = useMe(["CASHIER", "ADMIN"]);
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [category, setCategory] = useState<string | null>(null);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [picking, setPicking] = useState<MenuItem | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [hasPending] = useState(() => loadPending() !== null);

  useEffect(() => {
    if (!me) return;
    api
      .get<Catalog>("/menu")
      .then((c) => {
        setCatalog(c);
        setCategory((current) => current ?? c.categories[0]?.id ?? null);
      })
      .catch((e) => setError(e instanceof ApiError ? e.message : t("offline")));
  }, [me, t]);

  const items = useMemo(
    () => catalog?.categories.find((c) => c.id === category)?.items ?? [],
    [catalog, category],
  );
  const preview = cart.reduce((sum, l) => sum + linePreview(l), 0);

  function addLine(item: MenuItem, optionIds: string[]) {
    setCart((c) => [...c, { uid: crypto.randomUUID(), item, quantity: 1, optionIds }]);
    setPicking(null);
  }

  async function getQuote() {
    setBusy(true);
    setError(null);
    try {
      const quote = await api.post<Quote>("/orders/quotes", {
        lines: cart.map((l) => ({ menuItemId: l.item.id, quantity: l.quantity, optionIds: l.optionIds })),
      });
      saveQuote(quote);
      router.push("/pos/confirm-external-action");
    } catch (e) {
      setError(e instanceof ApiError ? e.message : e instanceof NoResponseError ? t("offline") : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell me={me} title="POS">
      {hasPending && (
        <div role="alert" className="alert mb-4 flex flex-wrap items-center gap-3">
          <span className="font-semibold">{t("timeoutBody")}</span>
          <Link className="btn-primary" href="/pos/recover-by-key">
            {t("checkStatus")}
          </Link>
        </div>
      )}
      <div className="grid gap-4 lg:grid-cols-[1fr_22rem]">
        <section>
          <div className="mb-3 flex flex-wrap gap-2">
            {catalog?.categories.map((c) => (
              <button
                key={c.id}
                type="button"
                className={c.id === category ? "btn-primary" : "btn-secondary"}
                onClick={() => setCategory(c.id)}
              >
                {c.title}
              </button>
            ))}
          </div>
          {catalog && catalog.categories.length === 0 && <p className="text-stone-500">{t("noProducts")}</p>}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
            {items.map((item) => (
              <button
                key={item.id}
                type="button"
                disabled={!item.available || hasPending}
                onClick={() => (item.modifierGroups.length ? setPicking(item) : addLine(item, []))}
                className="card flex min-h-24 flex-col items-start gap-1 text-left hover:border-stone-500 disabled:opacity-50"
              >
                <span className="font-semibold break-words">{item.title}</span>
                <span className="text-stone-600">{item.basePrice}</span>
                {!item.available && <span className="text-sm font-bold text-rose-700">{t("soldOut")}</span>}
                {item.available && item.lowStockFlag && (
                  <span className="text-sm font-bold text-amber-700">{t("lowStock")}</span>
                )}
              </button>
            ))}
          </div>
        </section>

        <aside className="card flex h-fit flex-col gap-3">
          <h2 className="text-lg font-semibold">{t("cart")}</h2>
          {cart.length === 0 && <p className="text-stone-500">{t("cartEmpty")}</p>}
          <ul className="flex flex-col gap-2">
            {cart.map((l) => (
              <li key={l.uid} className="rounded-lg border border-stone-200 p-2">
                <div className="flex items-start justify-between gap-2">
                  <span className="font-medium break-words">{l.item.title}</span>
                  <button type="button" className="btn-secondary" onClick={() => setCart((c) => c.filter((x) => x.uid !== l.uid))}>
                    {t("remove")}
                  </button>
                </div>
                {l.optionIds.length > 0 && (
                  <p className="text-sm text-stone-600">
                    {l.item.modifierGroups.flatMap((g) => g.options).filter((o) => l.optionIds.includes(o.id)).map((o) => o.title).join(", ")}
                  </p>
                )}
                <div className="mt-1 flex items-center gap-2">
                  <span className="text-sm">{t("quantity")}</span>
                  <button type="button" className="btn-secondary" aria-label="-"
                          onClick={() => setCart((c) => c.map((x) => x.uid === l.uid ? { ...x, quantity: Math.max(1, x.quantity - 1) } : x))}>
                    −
                  </button>
                  <span className="w-8 text-center font-semibold">{l.quantity}</span>
                  <button type="button" className="btn-secondary" aria-label="+"
                          onClick={() => setCart((c) => c.map((x) => x.uid === l.uid ? { ...x, quantity: Math.min(99, x.quantity + 1) } : x))}>
                    +
                  </button>
                </div>
              </li>
            ))}
          </ul>
          <p className="flex justify-between text-lg font-semibold">
            <span>{t("total")}</span>
            <span>
              ≈ {money(preview)} {catalog?.currency}
            </span>
          </p>
          <p className="text-sm text-stone-600">{t("provisional")}</p>
          {error && <p role="alert" className="alert">{error}</p>}
          <button
            type="button"
            className="btn-primary"
            disabled={cart.length === 0 || busy || hasPending}
            onClick={() => {
              clearQuote();
              void getQuote();
            }}
          >
            {t("getQuote")}
          </button>
        </aside>
      </div>
      {picking && <ModifierPicker item={picking} onCancel={() => setPicking(null)} onAdd={(ids) => addLine(picking, ids)} />}
    </Shell>
  );
}

function ModifierPicker({ item, onCancel, onAdd }: { item: MenuItem; onCancel: () => void; onAdd: (ids: string[]) => void }) {
  const t = useT();
  const [selected, setSelected] = useState<Record<string, string[]>>({});
  const valid = item.modifierGroups.every((g) => {
    const n = selected[g.id]?.length ?? 0;
    return n >= g.minSelected && n <= g.maxSelected;
  });

  function toggle(groupId: string, optionId: string, max: number) {
    setSelected((s) => {
      const current = s[groupId] ?? [];
      if (current.includes(optionId)) return { ...s, [groupId]: current.filter((x) => x !== optionId) };
      if (max === 1) return { ...s, [groupId]: [optionId] };
      if (current.length >= max) return s;
      return { ...s, [groupId]: [...current, optionId] };
    });
  }

  return (
    <div role="dialog" aria-modal="true" className="fixed inset-0 z-10 flex items-end justify-center bg-black/40 p-4 sm:items-center">
      <div className="card flex max-h-full w-full max-w-lg flex-col gap-4 overflow-auto">
        <h2 className="text-lg font-semibold">{item.title}</h2>
        {item.modifierGroups.map((g) => (
          <fieldset key={g.id} className="flex flex-col gap-2">
            <legend className="font-medium">
              {g.title}{" "}
              <span className="text-sm text-stone-500">
                ({t("choose")} {g.minSelected === g.maxSelected ? g.minSelected : `${g.minSelected}–${g.maxSelected}`})
              </span>
            </legend>
            <div className="flex flex-wrap gap-2">
              {g.options.map((o) => {
                const on = selected[g.id]?.includes(o.id) ?? false;
                return (
                  <button key={o.id} type="button" aria-pressed={on} className={on ? "btn-primary" : "btn-secondary"}
                          onClick={() => toggle(g.id, o.id, g.maxSelected)}>
                    {o.title} {o.priceDelta !== "0.00" && `+${o.priceDelta}`}
                  </button>
                );
              })}
            </div>
          </fieldset>
        ))}
        <div className="flex justify-end gap-2">
          <button type="button" className="btn-secondary" onClick={onCancel}>{t("cancel")}</button>
          <button type="button" className="btn-primary" disabled={!valid} onClick={() => onAdd(Object.values(selected).flat())}>
            {t("add")}
          </button>
        </div>
      </div>
    </div>
  );
}
