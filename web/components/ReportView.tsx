"use client";

import type { Report } from "@/lib/admin";
import { useTr } from "@/lib/i18n";

/** Section 5 totals. Internal record, never presented as a fiscal reconciliation. */
export function ReportView({ report }: { report: Report }) {
  const tr = useTr();
  const variance = report.variance;
  const sign = variance === null ? null : variance.startsWith("-") ? "short" : variance === "0.00" ? "even" : "over";
  const rows: [string, string][] = [
    [tr("Наличные", "Cash"), report.cashTotal],
    [tr("Карта (терминал)", "Card (terminal)"), report.externalCardTotal],
    ["QR", report.externalQrTotal],
    [tr("Итого записано", "Recorded total"), report.recordedTenderTotal],
  ];
  return (
    <div className="card flex flex-col gap-3">
      <p className="text-sm font-semibold text-amber-800">
        {tr("Внутренний учёт, не фискальная сверка.", "Internal record, not a fiscal reconciliation.")}
      </p>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-1 sm:max-w-md">
        {rows.map(([k, v]) => (
          <div key={k} className="contents">
            <dt>{k}</dt>
            <dd className="text-right font-mono font-semibold">{v} {report.currency}</dd>
          </div>
        ))}
        <dt>{tr("Пересчитано наличных", "Counted cash")}</dt>
        <dd className="text-right font-mono">{report.countedCash ?? "—"}</dd>
        <dt>{tr("Расхождение", "Variance")}</dt>
        <dd className={`text-right font-mono font-bold ${sign === "short" ? "text-rose-700" : sign === "over" ? "text-amber-700" : ""}`}>
          {variance === null
            ? "—"
            : `${variance.startsWith("-") ? "" : "+"}${variance} (${sign === "short" ? tr("недостача", "short") : sign === "over" ? tr("излишек", "over") : tr("сходится", "even")})`}
        </dd>
      </dl>
      <p className="text-sm text-stone-700">
        {tr("Заказов", "Orders")}: {report.orderCount} · {tr("учебных", "training")}: {report.trainingOrderCount} ·{" "}
        {tr("восстановленных", "recovered")}: {report.exceptionOrderCount} · {tr("ожидающих отмен", "pending voids")}:{" "}
        <b>{report.pendingExternalCorrections}</b>
      </p>
      {report.topItems.length > 0 && (
        <div>
          <h3 className="font-semibold">{tr("Топ позиций", "Top items")}</h3>
          <ol className="list-decimal pl-6 text-sm">
            {report.topItems.map((i) => (
              <li key={i.menuItemId}>{i.title} — {i.quantity}</li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}
