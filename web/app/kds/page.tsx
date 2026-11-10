"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { Shell, StatusBadge } from "@/components/Shell";
import { api, ApiError, type PrepStatus, type Ticket } from "@/lib/api";
import { useT, type MessageKey } from "@/lib/i18n";
import { useMe } from "@/lib/session";

type Snapshot = { snapshotAt: string; tickets: Ticket[] };

const NEXT: Partial<Record<PrepStatus, { to: PrepStatus; label: MessageKey }>> = {
  NEW: { to: "PREPARING", label: "start" },
  PREPARING: { to: "READY", label: "ready" },
  READY: { to: "CLEARED", label: "clear" },
};

const COLUMNS: PrepStatus[] = ["NEW", "PREPARING", "READY"];
const RECONNECT_MS = 5_000;

export default function KdsPage() {
  const t = useT();
  const router = useRouter();
  const me = useMe(["BARISTA", "ADMIN"]);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [stale, setStale] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  // the database snapshot is the truth; SSE events are only hints to reload it
  const reload = useCallback(
    () =>
      api
        .get<Snapshot>("/kds/open-tickets")
        .then(setSnapshot)
        .catch((e) => {
          if (e instanceof ApiError && e.status === 401) router.replace("/login");
          else setStale(true);
        }),
    [router],
  );

  useEffect(() => {
    if (!me) return;
    let source: EventSource | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let stopped = false;

    const connect = () => {
      source = new EventSource("/api/v1/kds/stream");
      source.onopen = () => {
        setStale(false);
        void reload();
      };
      source.addEventListener("ticket.changed", () => void reload());
      source.onerror = () => {
        setStale(true);
        if (source?.readyState === EventSource.CLOSED && !stopped) {
          // the browser gave up (for example the session ended): check the session, then reconnect
          api
            .get("/auth/me")
            .then(() => {
              if (!stopped) timer = setTimeout(connect, RECONNECT_MS);
            })
            .catch((e) => {
              if (e instanceof ApiError && e.status === 401) router.replace("/login");
              else if (!stopped) timer = setTimeout(connect, RECONNECT_MS);
            });
        }
      };
    };

    const onFocus = () => void reload();
    void reload();
    connect();
    window.addEventListener("focus", onFocus);
    return () => {
      stopped = true;
      clearTimeout(timer);
      source?.close();
      window.removeEventListener("focus", onFocus);
    };
  }, [me, reload, router]);

  async function advance(ticket: Ticket) {
    const next = NEXT[ticket.prepStatus];
    if (!next) return;
    setBusy(ticket.orderId);
    try {
      await api.patch(`/orders/${ticket.orderId}/prep-status`, { prepStatus: next.to, expectedVersion: ticket.version });
    } catch {
      // 409 VERSION_CONFLICT or INVALID_TRANSITION: someone else moved it; the reload shows the truth
    } finally {
      setBusy(null);
      void reload();
    }
  }

  const tickets = snapshot?.tickets ?? [];

  return (
    <Shell me={me} title={t("kds")}>
      {stale && (
        <p role="status" className="alert mb-3 font-semibold">
          {t("stale")}
        </p>
      )}
      <p className="mb-3 text-sm text-stone-600">
        {t("lastUpdated")}: {snapshot ? new Date(snapshot.snapshotAt).toLocaleTimeString() : "—"}
      </p>
      {snapshot && tickets.length === 0 && <p className="text-xl text-stone-500">{t("noTickets")}</p>}
      <div className="grid gap-4 md:grid-cols-3">
        {COLUMNS.map((column) => (
          <section key={column} className="flex flex-col gap-3">
            <h2 className="flex items-center gap-2 text-lg font-semibold">
              <StatusBadge status={column} />
              <span className="text-stone-500">{tickets.filter((x) => x.prepStatus === column).length}</span>
            </h2>
            {tickets
              .filter((x) => x.prepStatus === column)
              .map((ticket) => {
                const next = NEXT[ticket.prepStatus];
                return (
                  <article key={ticket.orderId} className="card flex flex-col gap-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-3xl font-bold">№ {ticket.humanNo}</span>
                      <StatusBadge status={ticket.prepStatus} />
                    </div>
                    <p className="text-sm text-stone-500">{new Date(ticket.placedAt).toLocaleTimeString()}</p>
                    <ul className="flex flex-col gap-1">
                      {ticket.lines.map((l) => (
                        <li key={l.lineNo} className="break-words">
                          <span className="text-lg font-semibold">
                            {l.quantity} × {l.title}
                          </span>
                          {l.modifiers.length > 0 && (
                            <span className="block text-stone-700">{l.modifiers.map((m) => m.title).join(", ")}</span>
                          )}
                        </li>
                      ))}
                    </ul>
                    {next && (
                      <button
                        type="button"
                        className="btn-primary"
                        disabled={busy === ticket.orderId}
                        onClick={() => void advance(ticket)}
                      >
                        {t(next.label)}
                      </button>
                    )}
                  </article>
                );
              })}
          </section>
        ))}
      </div>
    </Shell>
  );
}
