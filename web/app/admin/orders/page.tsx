"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { AdminShell, DateField, Notice } from "@/components/AdminShell";
import { StatusBadge } from "@/components/Shell";
import { errorText, type OrderSummary } from "@/lib/admin";
import { api } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

export default function AdminOrdersPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [picked, setPicked] = useState<string | null>(null);
  const [orders, setOrders] = useState<OrderSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const date = picked ?? me?.businessDate ?? "";

  useEffect(() => {
    if (!me || !date) return;
    api
      .get<OrderSummary[]>(`/admin/orders?businessDate=${date}`)
      .then((o) => {
        setOrders(o);
        setError(null);
      })
      .catch((e) => setError(errorText(e, t("offline"))));
  }, [me, date, t]);

  const voidLabel = (s: OrderSummary["voidState"]) =>
    s === "CONFIRMED" ? tr("отменён", "voided") : s === "PENDING" ? tr("отмена ожидает", "void pending") : "";

  return (
    <AdminShell me={me} title={tr("Заказы и отмены", "Orders and voids")}>
      <div className="flex flex-col gap-4">
        <DateField label={tr("Бизнес-дата", "Business date")} value={date} onChange={setPicked} />
        {error && <Notice kind="error">{error}</Notice>}
        {orders && orders.length === 0 && <p className="text-stone-500">{tr("Заказов нет.", "No orders.")}</p>}
        {orders && orders.length > 0 && (
          <div className="card overflow-x-auto">
            <table className="w-full [&_td]:px-2 [&_th]:px-2 min-w-[40rem] text-sm">
              <thead className="text-left text-stone-500">
                <tr>
                  <th className="py-1">№</th>
                  <th>{tr("Время", "Time")}</th>
                  <th>{tr("Кассир", "Cashier")}</th>
                  <th>{tr("Оплата", "Tender")}</th>
                  <th className="text-right">{tr("Сумма", "Total")}</th>
                  <th>{tr("Кухня", "Kitchen")}</th>
                  <th>{tr("Учёт", "Record")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {orders.map((o) => (
                  <tr key={o.id} className="border-t border-stone-100">
                    <td className="py-2 text-lg font-bold">{o.humanNo}</td>
                    <td>{new Date(o.placedAt).toLocaleTimeString()}</td>
                    <td>{o.cashierName}</td>
                    <td>{t(o.paymentMethod as "CASH")}</td>
                    <td className={`text-right font-mono ${o.voidState === "CONFIRMED" ? "line-through text-stone-400" : ""}`}>
                      {o.total}
                    </td>
                    <td><StatusBadge status={o.prepStatus} /></td>
                    <td>
                      {o.recordStatus}
                      {o.voidState && (
                        <span className={`ml-1 font-semibold ${o.voidState === "CONFIRMED" ? "text-rose-700" : "text-amber-700"}`}>
                          · {voidLabel(o.voidState)}
                        </span>
                      )}
                    </td>
                    <td>
                      <Link className="btn-secondary" href={`/admin/orders/${o.id}/correct`}>
                        {tr("Открыть", "Open")}
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </AdminShell>
  );
}
