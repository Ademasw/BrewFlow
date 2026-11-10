"use client";

import { ApiError, NoResponseError, type Role } from "./api";

export type Staff = {
  id: string;
  login: string;
  displayName: string;
  role: Role;
  active: boolean;
  mustChangeCredential: boolean;
  locked: boolean;
  revision: number;
  createdAt: string;
};

export type Shift = {
  id: string;
  businessDate: string;
  status: "OPEN" | "CLOSED";
  openedBy: string;
  openedAt: string;
  closedBy: string | null;
  closedAt: string | null;
  countedCash: string | null;
};

export type Report = {
  businessDate: string;
  shiftId: string | null;
  currency: string;
  cashTotal: string;
  externalCardTotal: string;
  externalQrTotal: string;
  recordedTenderTotal: string;
  pendingExternalCorrections: number;
  countedCash: string | null;
  variance: string | null;
  orderCount: number;
  trainingOrderCount: number;
  exceptionOrderCount: number;
  topItems: { menuItemId: string; title: string; quantity: number }[];
  label: string;
};

export type OrderSummary = {
  id: string;
  humanNo: number;
  placedAt: string;
  recordStatus: string;
  prepStatus: string;
  total: string;
  paymentMethod: string;
  cashierName: string;
  voidState: "CONFIRMED" | "PENDING" | null;
};

export type AdminOption = { id: string; groupId: string; title: string; priceDelta: string; active: boolean; revision: number };
export type AdminGroup = {
  id: string;
  title: string;
  minSelected: number;
  maxSelected: number;
  active: boolean;
  revision: number;
  options: AdminOption[];
};
export type AdminItem = {
  id: string;
  categoryId: string;
  title: string;
  basePrice: string;
  available: boolean;
  lowStockFlag: boolean;
  active: boolean;
  revision: number;
  modifierGroups: AdminGroup[];
};
export type AdminCategory = { id: string; title: string; sortOrder: number; active: boolean; revision: number; items: AdminItem[] };
export type AdminCatalog = { revision: number; currency: string; categories: AdminCategory[]; modifierGroups: AdminGroup[] };

/** Amount typed by staff: up to ten digits and two decimals. */
export const MONEY = /^\d{1,10}(\.\d{1,2})?$/;

/** Exactly two decimals, as the recovery endpoint compares the attested amount character for character. */
export function twoDecimals(raw: string): string {
  const [whole, frac = ""] = raw.trim().split(".");
  return `${whole}.${frac.padEnd(2, "0").slice(0, 2)}`;
}

/** Temporary credential shown once to the admin: six digits for staff, a random password for an admin. */
export function temporaryCredential(role: Role): string {
  const bytes = crypto.getRandomValues(new Uint32Array(16));
  if (role !== "ADMIN") {
    return String(100000 + (bytes[0] % 900000));
  }
  const alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
  return Array.from(bytes, (b) => alphabet[b % alphabet.length]).join("");
}

export function errorText(e: unknown, offline: string): string {
  if (e instanceof ApiError) return `${e.message} (${e.code})`;
  if (e instanceof NoResponseError) return offline;
  return String(e);
}

/** Stale revision or version: the screen reloads and asks the admin to repeat. */
export function isStale(e: unknown): boolean {
  return e instanceof ApiError && (e.code === "MENU_CHANGED" || e.code === "VERSION_CONFLICT");
}
