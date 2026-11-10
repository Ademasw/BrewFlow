"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { api, setCsrf, type Me } from "@/lib/api";
import { setLocale, useLocale, useT } from "@/lib/i18n";

export function Shell({ me, title, children }: { me: Me | null; title: string; children: ReactNode }) {
  const t = useT();
  const locale = useLocale();
  const router = useRouter();

  async function signOut() {
    try {
      await api.post("/auth/logout");
    } finally {
      setCsrf(null);
      router.replace("/login");
    }
  }

  return (
    <div className="flex min-h-full flex-col bg-stone-50 text-stone-900">
      {me?.appMode === "TRAINING" && (
        <div role="status" className="bg-amber-300 px-4 py-2 text-center text-sm font-bold tracking-wide text-amber-950">
          {t("trainingBanner")}
        </div>
      )}
      <header className="flex flex-wrap items-center gap-3 border-b border-stone-200 bg-white px-4 py-2">
        <span className="text-lg font-bold">{t("appName")}</span>
        <span className="text-stone-500">/ {title}</span>
        <nav className="ml-auto flex items-center gap-2">
          {me && (me.role === "CASHIER" || me.role === "ADMIN") && (
            <Link className="btn-secondary" href="/pos">
              POS
            </Link>
          )}
          {me && (me.role === "BARISTA" || me.role === "ADMIN") && (
            <Link className="btn-secondary" href="/kds">
              {t("kds")}
            </Link>
          )}
          {me?.role === "ADMIN" && (
            <Link className="btn-secondary" href="/admin/shifts">
              {locale === "en" ? "Admin" : "Админ"}
            </Link>
          )}
          <button
            type="button"
            className="btn-secondary"
            onClick={() => setLocale(locale === "ru" ? "en" : "ru")}
            aria-label="Language"
          >
            {locale === "ru" ? "EN" : "RU"}
          </button>
          {me && (
            <>
              <span className="hidden text-sm text-stone-600 sm:inline">
                {me.displayName} · {me.role}
              </span>
              <button type="button" className="btn-secondary" onClick={signOut}>
                {t("signOut")}
              </button>
            </>
          )}
        </nav>
      </header>
      <main className="flex-1 p-4">{children}</main>
    </div>
  );
}

export function StatusBadge({ status }: { status: string }) {
  const t = useT();
  const color: Record<string, string> = {
    NEW: "bg-sky-100 text-sky-900 border-sky-400",
    PREPARING: "bg-amber-100 text-amber-900 border-amber-400",
    READY: "bg-emerald-100 text-emerald-900 border-emerald-500",
    CLEARED: "bg-stone-100 text-stone-700 border-stone-300",
    CANCELLED: "bg-rose-100 text-rose-900 border-rose-400",
  };
  // status is text plus color, never color alone
  return (
    <span className={`inline-block rounded border px-2 py-0.5 text-sm font-bold ${color[status] ?? ""}`}>
      {t(status as "NEW")}
    </span>
  );
}
