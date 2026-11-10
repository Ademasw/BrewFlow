"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Shell, StatusBadge } from "@/components/Shell";
import { api, ApiError, type Order } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { useMe } from "@/lib/session";

export default function OrderPage() {
  const t = useT();
  const me = useMe(["CASHIER", "ADMIN"]);
  const { id } = useParams<{ id: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!me) return;
    api
      .get<Order>(`/orders/${id}`)
      .then(setOrder)
      .catch((e) => setError(e instanceof ApiError ? e.message : t("offline")));
  }, [me, id, t]);

  return (
    <Shell me={me} title={t("order")}>
      {error && <p role="alert" className="alert">{error}</p>}
      {order && (
        <div className="card mx-auto flex max-w-lg flex-col gap-3">
          <div className="flex items-center justify-between gap-2">
            <h1 className="text-3xl font-bold">№ {order.humanNo}</h1>
            <StatusBadge status={order.prepStatus} />
          </div>
          <p className="text-sm text-stone-600">
            {order.recordStatus} · {new Date(order.placedAt).toLocaleString()} · {t("quoteRef")} {order.quoteRef}
          </p>
          <ul className="flex flex-col gap-1">
            {order.lines.map((l) => (
              <li key={l.lineNo} className="flex justify-between gap-2">
                <span className="break-words">
                  {l.quantity} × {l.title}
                  {l.modifiers.length > 0 && <span className="text-stone-500"> ({l.modifiers.map((m) => m.title).join(", ")})</span>}
                </span>
                <span>{l.lineTotal}</span>
              </li>
            ))}
          </ul>
          <p className="flex justify-between border-t border-stone-200 pt-2 text-xl font-bold">
            <span>{t("total")}</span>
            <span>{order.total} {order.currency}</span>
          </p>
          <p className="text-sm">
            {t(order.payment.method as "CASH")}
            {order.payment.externalReference && ` · ${order.payment.externalReference}`} — {t("recordedNotVerified")}
          </p>
          {order.corrections.length > 0 && (
            <div>
              <h2 className="font-semibold">{t("corrections")}</h2>
              <ul className="text-sm">
                {order.corrections.map((c) => (
                  <li key={c.id}>{c.type} · {c.externalActionState} · {c.reason}</li>
                ))}
              </ul>
            </div>
          )}
          <Link className="btn-primary" href="/pos">{t("newOrder")}</Link>
        </div>
      )}
    </Shell>
  );
}
