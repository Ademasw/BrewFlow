"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";
import { Shell } from "@/components/Shell";
import type { Me } from "@/lib/api";
import { useTr } from "@/lib/i18n";

export function AdminShell({ me, title, children }: { me: Me | null; title: string; children: ReactNode }) {
  const tr = useTr();
  const path = usePathname();
  const links: [string, string][] = [
    ["/admin/shifts", tr("Смена", "Shift")],
    ["/admin/reports", tr("Отчёт", "Report")],
    ["/admin/orders", tr("Заказы и отмены", "Orders and voids")],
    ["/admin/recover-quote", tr("Восстановление", "Recovery")],
    ["/admin/menu", tr("Меню", "Menu")],
    ["/admin/staff", tr("Персонал", "Staff")],
    ["/admin/audit", tr("Аудит", "Audit")],
  ];
  return (
    <Shell me={me} title={title}>
      <nav className="mb-4 flex flex-wrap gap-2" aria-label={tr("Разделы админки", "Admin sections")}>
        {links.map(([href, label]) => (
          <Link key={href} href={href} aria-current={path.startsWith(href) ? "page" : undefined}
                className={path.startsWith(href) ? "btn-primary" : "btn-secondary"}>
            {label}
          </Link>
        ))}
      </nav>
      {me ? children : <p className="text-stone-500">…</p>}
    </Shell>
  );
}

export function Notice({ kind, children }: { kind: "error" | "ok" | "warn"; children: ReactNode }) {
  const style = {
    error: "border-rose-400 bg-rose-50 text-rose-950",
    ok: "border-emerald-500 bg-emerald-50 text-emerald-950",
    warn: "border-amber-400 bg-amber-50 text-amber-950",
  }[kind];
  return (
    <div role={kind === "error" ? "alert" : "status"} className={`rounded-lg border-2 p-3 ${style}`}>
      {children}
    </div>
  );
}

export function DateField({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-sm font-medium">{label}</span>
      <input type="date" className="field max-w-48" value={value} onChange={(e) => onChange(e.target.value)} />
    </label>
  );
}
