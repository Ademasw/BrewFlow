"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError, type Me, type Role } from "./api";

export function homeFor(role: Role): string {
  return role === "BARISTA" ? "/kds" : role === "ADMIN" ? "/admin/shifts" : "/pos";
}

/**
 * Loads the signed-in staff member. Redirects to /login when there is no session and to /change-credential
 * while the first credential change is outstanding. The backend enforces roles; this only picks the screen.
 */
export function useMe(allowed: Role[]): Me | null {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const allowedKey = allowed.join(",");

  useEffect(() => {
    let cancelled = false;
    api
      .get<Me>("/auth/me")
      .then((m) => {
        if (cancelled) return;
        if (m.mustChangeCredential) {
          router.replace("/change-credential");
        } else if (!allowedKey.split(",").includes(m.role)) {
          router.replace(homeFor(m.role));
        } else {
          setMe(m);
        }
      })
      .catch((e) => {
        if (!cancelled && e instanceof ApiError && e.status === 401) {
          router.replace("/login");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [router, allowedKey]);

  return me;
}

// Pending submit survives timeout, refresh and the back button (section 4.3). Not a secret.
const PENDING = "bf.pendingSubmit";

export type PendingSubmit = {
  key: string;
  quoteId: string;
  quoteRef: string;
  body: { quoteId: string; paymentMethod: string; externalReference: string | null; confirmedExternalAction: true };
};

export function loadPending(): PendingSubmit | null {
  try {
    const raw = sessionStorage.getItem(PENDING);
    return raw ? (JSON.parse(raw) as PendingSubmit) : null;
  } catch {
    return null;
  }
}

export function savePending(p: PendingSubmit) {
  try {
    sessionStorage.setItem(PENDING, JSON.stringify(p));
  } catch {
    // storage unavailable: the key stays in memory for this page only
  }
}

export function clearPending() {
  try {
    sessionStorage.removeItem(PENDING);
  } catch {
    // nothing stored
  }
}

const QUOTE = "bf.currentQuote";

export function saveQuote(quote: unknown) {
  try {
    sessionStorage.setItem(QUOTE, JSON.stringify(quote));
  } catch {
    // ignore
  }
}

export function loadQuote<T>(): T | null {
  try {
    const raw = sessionStorage.getItem(QUOTE);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

export function clearQuote() {
  try {
    sessionStorage.removeItem(QUOTE);
  } catch {
    // ignore
  }
}
