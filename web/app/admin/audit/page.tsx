"use client";

import { useEffect, useState } from "react";
import { AdminShell, Notice } from "@/components/AdminShell";
import { errorText, type Staff } from "@/lib/admin";
import { api } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

type AuditEvent = {
  id: string;
  actorId: string | null;
  eventType: string;
  entityType: string | null;
  entityId: string | null;
  requestId: string | null;
  metadata: Record<string, unknown>;
  createdAt: string;
};
type Page = { items: AuditEvent[]; nextBefore: string | null };

const PAGE = 50;

export default function AuditPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [next, setNext] = useState<string | null>(null);
  const [names, setNames] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!me) return;
    Promise.all([api.get<Page>(`/admin/audit?limit=${PAGE}`), api.get<Staff[]>("/admin/staff")])
      .then(([page, staff]) => {
        setEvents(page.items);
        setNext(page.nextBefore);
        setNames(Object.fromEntries(staff.map((s) => [s.id, s.displayName])));
      })
      .catch((e) => setError(errorText(e, t("offline"))));
  }, [me, t]);

  async function more() {
    if (!next) return;
    try {
      const page = await api.get<Page>(`/admin/audit?limit=${PAGE}&before=${encodeURIComponent(next)}`);
      setEvents((e) => [...e, ...page.items]);
      setNext(page.nextBefore);
    } catch (e) {
      setError(errorText(e, t("offline")));
    }
  }

  return (
    <AdminShell me={me} title={tr("Аудит", "Audit")}>
      <div className="flex flex-col gap-4">
        {error && <Notice kind="error">{error}</Notice>}
        <p className="text-sm text-stone-600">
          {tr("Журнал только дополняется. PIN, пароли и токены в него не пишутся.", "The log is append-only. PINs, passwords and tokens are never written to it.")}
        </p>
        <div className="card overflow-x-auto">
          <table className="w-full [&_td]:px-2 [&_th]:px-2 min-w-[44rem] text-sm">
            <thead className="text-left text-stone-500">
              <tr>
                <th className="py-1">{tr("Время", "Time")}</th>
                <th>{tr("Кто", "Actor")}</th>
                <th>{tr("Событие", "Event")}</th>
                <th>{tr("Подробности", "Details")}</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e) => (
                <tr key={e.id} className="border-t border-stone-100 align-top">
                  <td className="py-1 whitespace-nowrap">{new Date(e.createdAt).toLocaleString()}</td>
                  <td>{e.actorId ? (names[e.actorId] ?? e.actorId.slice(0, 8)) : "—"}</td>
                  <td className="font-mono text-xs">{e.eventType}</td>
                  <td className="font-mono text-xs break-all text-stone-600">
                    {Object.keys(e.metadata).length > 0 ? JSON.stringify(e.metadata) : ""}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {next && (
          <button type="button" className="btn-secondary self-start" onClick={() => void more()}>
            {tr("Показать ещё", "Load more")}
          </button>
        )}
      </div>
    </AdminShell>
  );
}
