"use client";

import { api, ApiError, NoResponseError, type Order } from "./api";
import { clearPending, clearQuote, type PendingSubmit } from "./session";

export type SubmitOutcome =
  | { kind: "created"; order: Order }
  | { kind: "noResponse" }
  | { kind: "expired"; quoteRef: string }
  | { kind: "consumed"; orderId: string | null }
  | { kind: "rejected"; message: string };

/**
 * POST /orders with the pending submit's original key and body. The key is never re-minted on timeout,
 * refresh or retry (section 4.3); it is dropped only when the server answered that nothing was written.
 */
export async function submitPending(pending: PendingSubmit): Promise<SubmitOutcome> {
  try {
    const order = await api.post<Order>("/orders", pending.body, { "Idempotency-Key": pending.key });
    clearPending();
    clearQuote();
    return { kind: "created", order };
  } catch (e) {
    if (e instanceof NoResponseError) {
      return { kind: "noResponse" };
    }
    if (e instanceof ApiError) {
      if (e.status >= 500) {
        return { kind: "noResponse" };
      }
      if (e.code === "QUOTE_EXPIRED") {
        // keep the key: an admin recovery may store the order on it
        return { kind: "expired", quoteRef: pending.quoteRef };
      }
      if (e.code === "QUOTE_ALREADY_CONSUMED") {
        clearPending();
        return { kind: "consumed", orderId: (e.details.orderId as string | undefined) ?? null };
      }
      if (e.code === "IDEMPOTENCY_KEY_REUSED") {
        return { kind: "rejected", message: e.message };
      }
      // 401/403/422/409 other than the above: the server wrote nothing for this key
      clearPending();
      return { kind: "rejected", message: e.message };
    }
    return { kind: "noResponse" };
  }
}
