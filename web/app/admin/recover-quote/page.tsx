"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { AdminShell, DateField, Notice } from "@/components/AdminShell";
import { MONEY, errorText, twoDecimals } from "@/lib/admin";
import { api, ApiError, type Order } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

type ExpiredQuote = {
  quoteId: string;
  quoteRef: string;
  cashierId: string;
  cashierName: string;
  total: string;
  expiresAt: string;
  pendingExceptionIds: string[];
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function RecoverQuotePage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [picked, setPicked] = useState<string | null>(null);
  const [quotes, setQuotes] = useState<ExpiredQuote[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<ExpiredQuote | null>(null);
  const [recovered, setRecovered] = useState<Order | null>(null);
  const date = picked ?? me?.businessDate ?? "";

  const load = useCallback(
    () =>
      api
        .get<ExpiredQuote[]>(`/admin/quotes?status=EXPIRED_UNCONSUMED&businessDate=${date}`)
        .then((q) => {
          setQuotes(q);
          setError(null);
        }),
    [date],
  );

  useEffect(() => {
    if (me && date) load().catch((e) => setError(errorText(e, t("offline"))));
  }, [me, date, load, t]);

  async function resolveOutside(exceptionId: string) {
    try {
      await api.post(`/admin/quotes/exceptions/${exceptionId}/resolve-outside`);
      await load();
    } catch (e) {
      setError(errorText(e, t("offline")));
    }
  }

  return (
    <AdminShell me={me} title={tr("Восстановление чеков", "Receipt recovery")}>
      <div className="flex flex-col gap-4">
        <p className="max-w-3xl text-sm text-stone-700">
          {tr(
            "Кассир пробил чек на ККМ, но расчёт истёк и заказ не записался. Найдите расчёт по коду, который показывает касса, и восстановите заказ по замороженной цене. Второй чек не пробивать.",
            "The cashier issued a KKM receipt, but the quote expired and no order was recorded. Find the quote by the code the POS shows and recover the order at the frozen price. Do not issue a second receipt.",
          )}
        </p>
        <DateField label={tr("Бизнес-дата", "Business date")} value={date} onChange={setPicked} />
        {error && <Notice kind="error">{error}</Notice>}
        {recovered && (
          <Notice kind="ok">
            <p className="font-bold">
              {tr("Заказ восстановлен", "Order recovered")}: № {recovered.humanNo} · {recovered.total} {recovered.currency}
            </p>
            <p className="font-semibold">{tr("НЕ пробивайте второй чек: этот заказ записывает уже выданный.", "Do NOT issue another fiscal receipt: this order records the one already issued.")}</p>
            <Link className="underline" href={`/admin/orders/${recovered.id}/correct`}>{tr("Открыть заказ", "Open order")}</Link>
          </Notice>
        )}
        {quotes && quotes.length === 0 && (
          <p className="text-stone-500">{tr("Нет просроченных расчётов без заказа.", "No expired quotes without an order.")}</p>
        )}
        {quotes && quotes.length > 0 && (
          <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {quotes.map((q) => (
              <article key={q.quoteId} className={`card flex flex-col gap-2 ${selected?.quoteId === q.quoteId ? "border-stone-900" : ""}`}>
                <p className="font-mono text-3xl font-bold tracking-widest">{q.quoteRef}</p>
                <p>{q.cashierName} · <b className="font-mono">{q.total}</b></p>
                <p className="text-sm text-stone-500">{tr("истёк", "expired")} {new Date(q.expiresAt).toLocaleTimeString()}</p>
                {q.pendingExceptionIds.length > 0 && (
                  <div className="rounded border border-amber-400 bg-amber-50 p-2 text-sm">
                    <p>{tr("Есть исключение: сумма не совпала.", "Open exception: the amount did not match.")}</p>
                    {q.pendingExceptionIds.map((x) => (
                      <button key={x} type="button" className="btn-secondary mt-1" onClick={() => void resolveOutside(x)}>
                        {tr("Закрыть вне системы (без заказа)", "Resolve outside (no order)")}
                      </button>
                    ))}
                  </div>
                )}
                <button type="button" className="btn-primary" onClick={() => { setSelected(q); setRecovered(null); }}>
                  {tr("Восстановить", "Recover")}
                </button>
              </article>
            ))}
          </div>
        )}
        {selected && me && (
          <RecoverForm
            key={selected.quoteId}
            quote={selected}
            live={me.appMode === "LIVE"}
            onDone={(order) => {
              setRecovered(order);
              setSelected(null);
              void load();
            }}
            onMismatch={() => void load()}
          />
        )}
      </div>
    </AdminShell>
  );
}

function RecoverForm({ quote, live, onDone, onMismatch }: {
  quote: ExpiredQuote;
  live: boolean;
  onDone: (order: Order) => void;
  onMismatch: () => void;
}) {
  const tr = useTr();
  const t = useT();
  const methods = live ? ["CASH", "EXTERNAL_CARD", "EXTERNAL_QR"] : ["TRAINING"];
  const [amount, setAmount] = useState("");
  const [method, setMethod] = useState(methods[0]);
  const [reference, setReference] = useState("");
  const [reason, setReason] = useState("");
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ kind: "error" | "warn"; text: string } | null>(null);
  const valid = MONEY.test(amount.trim()) && reason.trim().length > 0 && (key.trim() === "" || UUID.test(key.trim()));

  async function submit() {
    setBusy(true);
    setMessage(null);
    try {
      const result = await api.post<{ order: Order }>(`/admin/quotes/${quote.quoteId}/recover`, {
        attestedAmount: twoDecimals(amount),
        paymentMethod: method,
        externalReference: reference.trim() || null,
        reason: reason.trim(),
        idempotencyKey: key.trim() || null,
      });
      onDone(result.order);
    } catch (e) {
      if (e instanceof ApiError && e.code === "AMOUNT_MISMATCH") {
        setMessage({
          kind: "warn",
          text: tr(
            `Сумма не совпала с расчётом (${String(e.details.snapshotTotal)}). Заказ не создан, записано исключение. Второй чек не пробивать.`,
            `The amount differs from the quote (${String(e.details.snapshotTotal)}). No order was created; an exception was recorded. Do not issue a second receipt.`,
          ),
        });
        onMismatch();
      } else {
        setMessage({ kind: "error", text: errorText(e, t("offline")) });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card flex max-w-xl flex-col gap-3">
      <h2 className="text-lg font-semibold">
        {tr("Восстановление", "Recover")} <span className="font-mono">{quote.quoteRef}</span>
      </h2>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{tr("Сумма по чеку ККМ", "Amount on the KKM receipt")}</span>
        <input className="field font-mono" inputMode="decimal" placeholder="0.00" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <span className="text-xs text-stone-500">{tr("Введите сумму с чека, а не из расчёта: система сверит их сама.", "Type the amount from the receipt, not from the quote; the system compares them.")}</span>
      </label>
      <fieldset className="flex flex-wrap gap-2">
        <legend className="mb-1 text-sm font-medium">{t("method")}</legend>
        {methods.map((m) => (
          <button key={m} type="button" aria-pressed={method === m} className={method === m ? "btn-primary" : "btn-secondary"} onClick={() => setMethod(m)}>
            {t(m as "CASH")}
          </button>
        ))}
      </fieldset>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{t("externalReference")}</span>
        <input className="field" maxLength={64} value={reference} onChange={(e) => setReference(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{tr("Причина", "Reason")}</span>
        <input className="field" maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{tr("Ключ с кассы (Idempotency-Key), если есть", "POS key (Idempotency-Key), if available")}</span>
        <input className="field font-mono text-sm" value={key} onChange={(e) => setKey(e.target.value)} />
      </label>
      {message && <Notice kind={message.kind}>{message.text}</Notice>}
      <button type="button" className="btn-primary" disabled={!valid || busy} onClick={() => void submit()}>
        {tr("Восстановить заказ", "Recover order")}
      </button>
    </section>
  );
}
