"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { ClientOnly } from "@/components/ClientOnly";
import { Shell } from "@/components/Shell";
import type { Quote } from "@/lib/api";
import { useT } from "@/lib/i18n";
import { loadPending, loadQuote, savePending, useMe, type PendingSubmit } from "@/lib/session";
import { submitPending, type SubmitOutcome } from "@/lib/submit";

const LIVE_METHODS = ["CASH", "EXTERNAL_CARD", "EXTERNAL_QR"] as const;

export default function ConfirmExternalActionPage() {
  return (
    <ClientOnly>
      <ConfirmExternalActionPageScreen />
    </ClientOnly>
  );
}

function ConfirmExternalActionPageScreen() {
  const t = useT();
  const router = useRouter();
  const me = useMe(["CASHIER", "ADMIN"]);
  const [quote] = useState<Quote | null>(() => loadQuote<Quote>());
  const [method, setMethod] = useState<string | null>(null);
  const [reference, setReference] = useState("");
  const [attested, setAttested] = useState(false);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<SubmitOutcome | null>(null);

  // TRAINING hides live methods; LIVE hides TRAINING
  const methods: readonly string[] = me?.appMode === "LIVE" ? LIVE_METHODS : ["TRAINING"];
  const chosen = method ?? (methods.length === 1 ? methods[0] : null);

  async function record() {
    if (!quote || !chosen) return;
    setBusy(true);
    let pending = loadPending();
    if (!pending || pending.quoteId !== quote.quoteId) {
      const fresh: PendingSubmit = {
        key: crypto.randomUUID(),
        quoteId: quote.quoteId,
        quoteRef: quote.quoteRef,
        body: {
          quoteId: quote.quoteId,
          paymentMethod: chosen,
          externalReference: reference.trim() || null,
          confirmedExternalAction: true,
        },
      };
      // stored before the first send, so a refresh during the request keeps the same key
      savePending(fresh);
      pending = fresh;
    }
    const result = await submitPending(pending);
    setBusy(false);
    if (result.kind === "created") {
      router.replace(`/orders/${result.order.id}`);
    } else if (result.kind === "noResponse") {
      router.replace("/pos/recover-by-key");
    } else {
      setOutcome(result);
    }
  }

  if (outcome?.kind === "expired") {
    return (
      <Shell me={me} title={t("confirmTitle")}>
        <div role="alert" className="alert mx-auto max-w-lg">
          <h1 className="text-xl font-bold">{t("expiredTitle")}</h1>
          <p className="mt-2">{t("expiredBody")}</p>
          <p className="mt-3 font-mono text-4xl font-bold tracking-widest">{outcome.quoteRef}</p>
        </div>
      </Shell>
    );
  }

  return (
    <Shell me={me} title={t("confirmTitle")}>
      {!quote ? (
        <p className="text-stone-500">
          <Link className="underline" href="/pos">POS</Link>
        </p>
      ) : (
        <div className="card mx-auto flex max-w-lg flex-col gap-4">
          <h1 className="text-xl font-semibold">{t("confirmTitle")}</h1>
          <p className="text-sm text-stone-600">
            {t("quoteRef")}: <span className="font-mono font-bold">{quote.quoteRef}</span> · {t("expiresAt")}{" "}
            {new Date(quote.expiresAt).toLocaleTimeString()}
          </p>
          <ul className="flex flex-col gap-1">
            {quote.lines.map((l) => (
              <li key={l.lineNo} className="flex justify-between gap-2">
                <span className="break-words">
                  {l.quantity} × {l.title}
                  {l.modifiers.length > 0 && <span className="text-stone-500"> ({l.modifiers.map((m) => m.title).join(", ")})</span>}
                </span>
                <span>{l.lineTotal}</span>
              </li>
            ))}
          </ul>
          <p className="flex justify-between border-t border-stone-200 pt-2 text-2xl font-bold">
            <span>{t("total")}</span>
            <span>{quote.total} {quote.currency}</span>
          </p>

          <fieldset className="flex flex-col gap-2">
            <legend className="font-medium">{t("method")}</legend>
            <div className="flex flex-wrap gap-2">
              {methods.map((m) => (
                <button key={m} type="button" aria-pressed={chosen === m} className={chosen === m ? "btn-primary" : "btn-secondary"}
                        onClick={() => setMethod(m)}>
                  {t(m as "CASH")}
                </button>
              ))}
            </div>
          </fieldset>
          <label className="flex flex-col gap-1">
            <span className="text-sm font-medium">{t("externalReference")}</span>
            <input className="field" maxLength={64} value={reference} onChange={(e) => setReference(e.target.value)} />
          </label>
          <label className="flex min-h-12 items-center gap-3">
            <input type="checkbox" className="size-6" checked={attested} onChange={(e) => setAttested(e.target.checked)} />
            <span className="font-medium">{t("attest")}</span>
          </label>
          <p className="text-sm text-stone-600">{t("recordedNotVerified")}</p>
          {outcome?.kind === "consumed" && (
            <div role="alert" className="alert">
              <p className="font-semibold">{t("consumedTitle")}</p>
              {outcome.orderId && <Link className="btn-primary mt-2" href={`/orders/${outcome.orderId}`}>{t("openOrder")}</Link>}
            </div>
          )}
          {outcome?.kind === "rejected" && <p role="alert" className="alert">{outcome.message}</p>}
          <button type="button" className="btn-primary" disabled={!attested || !chosen || busy} onClick={() => void record()}>
            {t("recordOrder")}
          </button>
        </div>
      )}
    </Shell>
  );
}
