"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell, Notice } from "@/components/AdminShell";
import { ReportView } from "@/components/ReportView";
import { MONEY, errorText, type Report, type Shift } from "@/lib/admin";
import { api } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

export default function ShiftsPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [shifts, setShifts] = useState<Shift[] | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [counted, setCounted] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [closed, setClosed] = useState<{ shift: Shift; report: Report } | null>(null);

  const open = shifts?.find((s) => s.status === "OPEN") ?? null;

  const load = useCallback(
    () =>
      api.get<Shift[]>("/admin/shifts").then(async (list) => {
        const current = list.find((s) => s.status === "OPEN");
        const shiftReport = current ? await api.get<Report>(`/admin/reports/daily?shiftId=${current.id}`) : null;
        setShifts(list);
        setReport(shiftReport);
      }),
    [],
  );

  useEffect(() => {
    if (me) load().catch((e) => setError(errorText(e, t("offline"))));
  }, [me, load, t]);

  async function openShift() {
    setBusy(true);
    setError(null);
    setClosed(null);
    try {
      await api.post("/admin/shifts/open");
      await load();
    } catch (e) {
      setError(errorText(e, t("offline")));
    } finally {
      setBusy(false);
    }
  }

  async function closeShift(e: FormEvent) {
    e.preventDefault();
    if (!open || !MONEY.test(counted.trim())) return;
    setBusy(true);
    setError(null);
    try {
      const result = await api.post<{ shift: Shift; report: Report }>(`/admin/shifts/${open.id}/close`, {
        countedCash: counted.trim(),
      });
      setClosed(result);
      setCounted("");
      await load();
    } catch (err) {
      setError(errorText(err, t("offline")));
      await load().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  return (
    <AdminShell me={me} title={tr("Смена", "Shift")}>
      <div className="flex flex-col gap-4">
        {error && <Notice kind="error">{error}</Notice>}
        {closed && (
          <section className="flex flex-col gap-2">
            <Notice kind="ok">
              {tr("Смена закрыта", "Shift closed")} ({closed.shift.businessDate}).
            </Notice>
            <ReportView report={closed.report} />
          </section>
        )}

        {shifts && !open && (
          <div className="card flex flex-wrap items-center gap-3">
            <p className="font-semibold">{tr("Смена закрыта: касса не принимает заказы.", "No shift is open: the POS cannot record orders.")}</p>
            <button type="button" className="btn-primary" disabled={busy} onClick={() => void openShift()}>
              {tr("Открыть смену", "Open shift")}
            </button>
          </div>
        )}

        {open && (
          <section className="flex flex-col gap-3">
            <h1 className="text-xl font-semibold">
              {tr("Открытая смена", "Open shift")} · {open.businessDate} · {tr("с", "since")}{" "}
              {new Date(open.openedAt).toLocaleTimeString()}
            </h1>
            {report && <ReportView report={report} />}
            <form onSubmit={closeShift} className="card flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1">
                <span className="text-sm font-medium">{tr("Пересчитано наличных", "Counted cash")}</span>
                <input className="field max-w-48 font-mono" inputMode="decimal" placeholder="0.00" value={counted}
                       onChange={(e) => setCounted(e.target.value)} required />
              </label>
              <button className="btn-primary" disabled={busy || !MONEY.test(counted.trim())}>
                {tr("Закрыть смену", "Close shift")}
              </button>
              <p className="w-full text-sm text-stone-600">
                {tr("Смена закрывается один раз. Расхождение = пересчитано − наличные по учёту.",
                  "A shift closes once. Variance = counted − recorded cash.")}
              </p>
            </form>
          </section>
        )}

        {shifts && shifts.length > 0 && (
          <section className="card">
            <h2 className="mb-2 font-semibold">{tr("Последние смены", "Recent shifts")}</h2>
            <table className="w-full [&_td]:px-2 [&_th]:px-2 text-sm">
              <thead className="text-left text-stone-500">
                <tr>
                  <th className="py-1">{tr("Дата", "Date")}</th>
                  <th>{tr("Статус", "Status")}</th>
                  <th>{tr("Открыта", "Opened")}</th>
                  <th>{tr("Закрыта", "Closed")}</th>
                  <th className="text-right">{tr("Пересчитано", "Counted")}</th>
                </tr>
              </thead>
              <tbody>
                {shifts.map((s) => (
                  <tr key={s.id} className="border-t border-stone-100">
                    <td className="py-1">{s.businessDate}</td>
                    <td className={s.status === "OPEN" ? "font-bold text-emerald-700" : ""}>
                      {s.status === "OPEN" ? tr("ОТКРЫТА", "OPEN") : tr("ЗАКРЫТА", "CLOSED")}
                    </td>
                    <td>{new Date(s.openedAt).toLocaleString()}</td>
                    <td>{s.closedAt ? new Date(s.closedAt).toLocaleString() : "—"}</td>
                    <td className="text-right font-mono">{s.countedCash ?? "—"}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        )}
      </div>
    </AdminShell>
  );
}
