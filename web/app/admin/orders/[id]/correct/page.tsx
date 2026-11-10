"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { AdminShell, Notice } from "@/components/AdminShell";
import { StatusBadge } from "@/components/Shell";
import { errorText } from "@/lib/admin";
import { api, type Order } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

type State = "PENDING" | "CONFIRMED" | "NOT_APPLICABLE";

export default function CorrectOrderPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const { id } = useParams<{ id: string }>();
  const [order, setOrder] = useState<Order | null>(null);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = useCallback(() => api.get<Order>(`/orders/${id}`).then(setOrder), [id]);

  useEffect(() => {
    if (me) load().catch((e) => setError(errorText(e, t("offline"))));
  }, [me, load, t]);

  const notes = order?.corrections ?? [];
  const confirmed = notes.some((c) => c.externalActionState === "CONFIRMED");
  const pending = notes.find(
    (c) => c.externalActionState === "PENDING" && !notes.some((k) => k.confirmsCorrectionId === c.id),
  );

  async function append(state: State, confirms?: string) {
    if (!reason.trim()) {
      setError(tr("Укажите причину.", "Enter a reason."));
      return;
    }
    setBusy(true);
    setError(null);
    setDone(null);
    try {
      const updated = await api.post<Order>(`/admin/orders/${id}/corrections`, {
        type: "VOID_NOTE",
        externalActionState: state,
        confirmsCorrectionId: confirms ?? null,
        reason: reason.trim(),
      });
      setOrder(updated);
      setReason("");
      setDone(tr("Запись добавлена.", "Note added."));
    } catch (e) {
      setError(errorText(e, t("offline")));
      await load().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  const stateLabel = (s: string) =>
    s === "CONFIRMED" ? tr("подтверждена", "confirmed") : s === "PENDING" ? tr("ожидает", "pending") : tr("не требуется", "not applicable");

  return (
    <AdminShell me={me} title={tr("Отмена заказа", "Void order")}>
      <div className="flex max-w-2xl flex-col gap-4">
        <Link className="text-sm underline" href="/admin/orders">← {tr("К списку заказов", "Back to orders")}</Link>
        {error && <Notice kind="error">{error}</Notice>}
        {done && <Notice kind="ok">{done}</Notice>}
        {order && (
          <>
            <section className="card flex flex-col gap-2">
              <div className="flex items-center justify-between">
                <h1 className="text-2xl font-bold">№ {order.humanNo}</h1>
                <StatusBadge status={order.prepStatus} />
              </div>
              <p className="text-sm text-stone-600">
                {order.recordStatus} · {new Date(order.placedAt).toLocaleString()} · {t(order.payment.method as "CASH")}
                {order.payment.externalReference && ` · ${order.payment.externalReference}`}
              </p>
              <ul className="text-sm">
                {order.lines.map((l) => (
                  <li key={l.lineNo} className="flex justify-between">
                    <span>{l.quantity} × {l.title}{l.modifiers.length > 0 && ` (${l.modifiers.map((m) => m.title).join(", ")})`}</span>
                    <span className="font-mono">{l.lineTotal}</span>
                  </li>
                ))}
              </ul>
              <p className="text-right text-lg font-bold">{order.total} {order.currency}</p>
            </section>

            {notes.length > 0 && (
              <section className="card">
                <h2 className="mb-1 font-semibold">{tr("Записи об отмене", "Void notes")}</h2>
                <ul className="text-sm">
                  {notes.map((c) => (
                    <li key={c.id}>
                      {new Date(c.createdAt).toLocaleString()} · <b>{stateLabel(c.externalActionState)}</b> · {c.reason}
                    </li>
                  ))}
                </ul>
              </section>
            )}

            {confirmed ? (
              <Notice kind="warn">
                {tr("Заказ уже отменён. Записи не удаляются и не меняются.", "The order is already voided. Notes are never deleted or edited.")}
              </Notice>
            ) : (
              <section className="card flex flex-col gap-3">
                <h2 className="font-semibold">{tr("Отменить заказ целиком", "Void the whole order")}</h2>
                <p className="text-sm text-stone-700">
                  {tr(
                    "«Подтверждено» значит: вы проверили, что корректировка на ККМ уже проведена. Система это не проверяет. Сумма уйдёт из отчёта, открытый заказ исчезнет с кухни.",
                    "“Confirmed” means you attest the KKM correction is already done; the system does not verify it. The amount leaves the report and an open ticket leaves the kitchen.",
                  )}
                </p>
                <label className="flex flex-col gap-1">
                  <span className="text-sm font-medium">{tr("Причина", "Reason")}</span>
                  <input className="field" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
                </label>
                <div className="flex flex-wrap gap-2">
                  {pending ? (
                    <button type="button" className="btn-primary" disabled={busy} onClick={() => void append("CONFIRMED", pending.id)}>
                      {tr("Подтвердить ожидающую отмену", "Confirm the pending void")}
                    </button>
                  ) : (
                    <>
                      <button type="button" className="btn-secondary" disabled={busy} onClick={() => void append("PENDING")}>
                        {tr("Отмена ожидает корректировки ККМ", "Void pending KKM correction")}
                      </button>
                      <button type="button" className="btn-primary" disabled={busy} onClick={() => void append("CONFIRMED")}>
                        {tr("Отмена подтверждена", "Void confirmed")}
                      </button>
                    </>
                  )}
                  {me?.appMode === "TRAINING" && !pending && (
                    <button type="button" className="btn-secondary" disabled={busy} onClick={() => void append("NOT_APPLICABLE")}>
                      {tr("Пометка (обучение, ККМ не нужна)", "Note (training, no KKM)")}
                    </button>
                  )}
                </div>
              </section>
            )}
          </>
        )}
      </div>
    </AdminShell>
  );
}
