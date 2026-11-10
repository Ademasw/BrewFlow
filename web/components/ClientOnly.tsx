"use client";

import { useSyncExternalStore, type ReactNode } from "react";

const noop = () => () => {};

/** Renders children only in the browser, so state read from sessionStorage never mismatches server HTML. */
export function ClientOnly({ children }: { children: ReactNode }) {
  const browser = useSyncExternalStore(noop, () => true, () => false);
  return browser ? children : null;
}
