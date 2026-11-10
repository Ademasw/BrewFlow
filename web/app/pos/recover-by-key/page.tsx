"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { ClientOnly } from "@/components/ClientOnly";
import { Shell } from "@/components/Shell";
import { api, ApiError, type Order } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { clearPending, clearQuote, loadPending, useMe } from "@/lib/session";
import { submitPending, type SubmitOutcome } from "@/lib/submit";

type State = "checking" | "notFound" | "offline" | "none";

export default function RecoverByKeyPage() {
  return (
    <ClientOnly>
      <RecoverByKeyPageScreen />
    </ClientOnly>
  );
}

function RecoverByKeyPageScreen() {
  const t = useT();
  const router = useRouter();
  const me = useMe(["CASHIER", "ADMIN"]);
  const [pending] = useState(() => loadPending());
  const [state, setState] = useState<State>(pending ? "checking" : "none");
  const [outcome, setOutcome] = useState<SubmitOutcome | null>(null);

  const lookup = useCallback(() => {
    if (!pending) return Promise.resolve();
    return api
      .get<Order>(`/orders/by-key/${pending.key}`)
      .then((order) => {
        clearPending();
        clearQuote();
        router.replace(`/orders/${order.id}`);
      })
      .catch((e) => setState(e instanceof ApiError && e.status === 404 ? "notFound" : "offline"));
  }, [pending, router]);

  useEffect(() => {
    if (me && pending) {
      void lookup();
    }
  }, [me, pending, lookup]);

  function check() {
    setState("checking");
    void lookup();
  }

  async function repeat() {
    if (!pending) return;
    const result = await submitPending(pending);
    if (result.kind === "created") router.replace(`/orders/${result.order.id}`);
    else if (result.kind === "noResponse") setState("offline");
    else setOutcome(result);
  }

  return (
    <Shell me={me} title={t("recoverTitle")}>
      <div className="card mx-auto flex max-w-lg flex-col gap-4">
        <h1 className="text-xl font-semibold">{t("recoverTitle")}</h1>
        {!pending && (
          <>
            <p>{t("noPending")}</p>
            <Link className="btn-primary" href="/pos">{t("newOrder")}</Link>
          </>
        )}
        {pending && (
          <>
            <p className="font-semibold text-rose-800">{t("timeoutBody")}</p>
            <p>
              {t("quoteRef")}: <span className="font-mono text-2xl font-bold">{pending.quoteRef}</span>
            </p>
            <p className="break-all font-mono text-xs text-stone-500">Idempotency-Key {pending.key}</p>
            {state === "checking" && <p className="text-stone-500">…</p>}
            {state === "offline" && (
              <>
                <p role="alert" className="alert">{t("offline")}</p>
                <button type="button" className="btn-primary" onClick={check}>{t("checkStatus")}</button>
              </>
            )}
            {state === "notFound" && !outcome && (
              <>
                <p>{t("notFoundYet")}</p>
                <button type="button" className="btn-primary" onClick={() => void repeat()}>{t("repeatSame")}</button>
              </>
            )}
            {outcome?.kind === "expired" && (
              <div role="alert" className="alert">
                <p className="font-bold">{t("expiredTitle")}</p>
                <p>{t("expiredBody")}</p>
                <p className="font-mono text-4xl font-bold tracking-widest">{outcome.quoteRef}</p>
              </div>
            )}
            {outcome?.kind === "consumed" && (
              <div role="alert" className="alert">
                <p className="font-semibold">{t("consumedTitle")}</p>
                {outcome.orderId && <Link className="btn-primary mt-2" href={`/orders/${outcome.orderId}`}>{t("openOrder")}</Link>}
              </div>
            )}
            {outcome?.kind === "rejected" && <p role="alert" className="alert">{outcome.message}</p>}
          </>
        )}
      </div>
    </Shell>
  );
}
