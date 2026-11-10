"use client";

import { useRouter } from "next/navigation";
import { useState, type FormEvent } from "react";
import { Shell } from "@/components/Shell";
import { api, ApiError, NoResponseError, setCsrf, type LoginResponse } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { homeFor } from "@/lib/session";

export default function LoginPage() {
  const t = useT();
  const router = useRouter();
  const [login, setLogin] = useState("");
  const [credential, setCredential] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // a fresh login (or staff switch) gets a new session and a new CSRF token
      setCsrf(null);
      const me = await api.post<LoginResponse>("/auth/login", { login, credential });
      setCsrf(me.csrf);
      setCredential("");
      router.replace(me.mustChangeCredential ? "/change-credential" : homeFor(me.role));
    } catch (err) {
      if (err instanceof ApiError && err.code === "TOO_MANY_ATTEMPTS") setError(t("locked"));
      else if (err instanceof NoResponseError) setError(t("offline"));
      else setError(t("wrongCredential"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Shell me={null} title={t("signIn")}>
      <form onSubmit={submit} className="card mx-auto mt-8 flex max-w-sm flex-col gap-4">
        <label className="flex flex-col gap-1">
          <span className="text-sm font-medium">{t("login")}</span>
          <input className="field" autoComplete="username" value={login} onChange={(e) => setLogin(e.target.value)} required />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm font-medium">{t("credential")}</span>
          <input
            className="field"
            type="password"
            autoComplete="current-password"
            value={credential}
            onChange={(e) => setCredential(e.target.value)}
            required
          />
        </label>
        {error && (
          <p role="alert" className="alert">
            {error}
          </p>
        )}
        <button className="btn-primary" disabled={busy}>
          {t("signIn")}
        </button>
      </form>
    </Shell>
  );
}
