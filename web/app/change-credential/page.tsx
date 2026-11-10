"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState, type FormEvent } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiError, type Me } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { homeFor } from "@/lib/session";

export default function ChangeCredentialPage() {
  const t = useT();
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .get<Me>("/auth/me")
      .then(setMe)
      .catch(() => router.replace("/login"));
  }, [router]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.post("/auth/credential", { currentCredential: current, newCredential: next });
      const updated = await api.get<Me>("/auth/me");
      router.replace(homeFor(updated.role));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : t("offline"));
    }
  }

  return (
    <Shell me={me} title={t("changeTitle")}>
      <form onSubmit={submit} className="card mx-auto mt-8 flex max-w-sm flex-col gap-4">
        <h1 className="text-lg font-semibold">{t("changeTitle")}</h1>
        <label className="flex flex-col gap-1">
          <span className="text-sm font-medium">{t("current")}</span>
          <input className="field" type="password" autoComplete="current-password" value={current}
                 onChange={(e) => setCurrent(e.target.value)} required />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm font-medium">{t("next")}</span>
          <input className="field" type="password" autoComplete="new-password" value={next}
                 onChange={(e) => setNext(e.target.value)} required />
          <span className="text-xs text-stone-500">PIN: 6 digits · admin password: 12+ characters</span>
        </label>
        {error && <p role="alert" className="alert">{error}</p>}
        <button className="btn-primary">{t("save")}</button>
      </form>
    </Shell>
  );
}
