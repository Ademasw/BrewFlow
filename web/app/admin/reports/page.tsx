"use client";

import { useEffect, useState } from "react";
import { AdminShell, DateField, Notice } from "@/components/AdminShell";
import { ReportView } from "@/components/ReportView";
import { errorText, type Report } from "@/lib/admin";
import { api } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

export default function ReportsPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [picked, setPicked] = useState<string | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState<string | null>(null);
  const date = picked ?? me?.businessDate ?? "";

  useEffect(() => {
    if (!me || !date) return;
    api
      .get<Report>(`/admin/reports/daily?businessDate=${date}`)
      .then((r) => {
        setReport(r);
        setError(null);
      })
      .catch((e) => setError(errorText(e, t("offline"))));
  }, [me, date, t]);

  return (
    <AdminShell me={me} title={tr("Отчёт", "Report")}>
      <div className="flex flex-col gap-4">
        <DateField label={tr("Бизнес-дата", "Business date")} value={date} onChange={setPicked} />
        {error && <Notice kind="error">{error}</Notice>}
        {report && <ReportView report={report} />}
        <p className="text-sm text-stone-600">
          {tr("Учебные (TRAINING) оплаты в суммы не входят. Подтверждённая отмена убирает всю сумму заказа; ожидающая — нет.",
            "TRAINING payments are excluded. A confirmed void removes the full order amount; a pending one does not.")}
        </p>
      </div>
    </AdminShell>
  );
}
