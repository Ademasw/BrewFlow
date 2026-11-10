// Same-origin client for /api/v1. The CSRF token lives in memory only: no secret goes to web storage.

export type ErrorBody = {
  error: { code: string; message: string; requestId?: string; details?: Record<string, unknown> };
};

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
    public details: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

/** Thrown when no HTTP response arrived (timeout, network drop). The request may or may not have committed. */
export class NoResponseError extends Error {}

type Csrf = { headerName: string; token: string };
let csrf: Csrf | null = null;

export function setCsrf(value: Csrf | null) {
  csrf = value;
}

async function loadCsrf(): Promise<Csrf> {
  if (!csrf) {
    csrf = await request<Csrf>("GET", "/auth/csrf");
  }
  return csrf;
}

const TIMEOUT_MS = 15_000;

async function request<T>(
  method: string,
  path: string,
  body?: unknown,
  headers: Record<string, string> = {},
): Promise<T> {
  try {
    return await send<T>(method, path, body, headers);
  } catch (e) {
    // a refused CSRF token wrote nothing on the server, so one retry with a fresh token is safe
    if (e instanceof ApiError && e.code === "CSRF_INVALID") {
      return send<T>(method, path, body, headers);
    }
    throw e;
  }
}

async function send<T>(
  method: string,
  path: string,
  body: unknown,
  headers: Record<string, string>,
): Promise<T> {
  const h: Record<string, string> = { Accept: "application/json", ...headers };
  if (method !== "GET") {
    const token = await loadCsrf();
    h[token.headerName] = token.token;
  }
  if (body !== undefined) {
    h["Content-Type"] = "application/json";
  }
  let res: Response;
  try {
    res = await fetch(`/api/v1${path}`, {
      method,
      headers: h,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "same-origin",
      cache: "no-store",
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    throw new NoResponseError("No response from the server.");
  }
  if (res.status === 204) {
    return undefined as T;
  }
  const text = await res.text();
  const json = text ? JSON.parse(text) : undefined;
  if (!res.ok) {
    const e = (json as ErrorBody | undefined)?.error;
    if (e?.code === "CSRF_INVALID" || res.status === 401) {
      csrf = null;
    }
    throw new ApiError(res.status, e?.code ?? "HTTP_" + res.status, e?.message ?? res.statusText, e?.details ?? {});
  }
  return json as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown, headers?: Record<string, string>) => request<T>("POST", path, body, headers),
  patch: <T>(path: string, body: unknown) => request<T>("PATCH", path, body),
  put: <T>(path: string, body: unknown) => request<T>("PUT", path, body),
};

// ---------------------------------------------------------------- types (section 7)

export type Role = "CASHIER" | "BARISTA" | "ADMIN";

export type Me = {
  id: string;
  login: string;
  displayName: string;
  role: Role;
  mustChangeCredential: boolean;
  idleTimeoutSeconds: number | null;
  appMode: "TRAINING" | "LIVE";
  currency: string;
  businessDate: string;
};

export type LoginResponse = Me & { csrf: Csrf };

export type MenuOption = { id: string; groupId: string; title: string; priceDelta: string };
export type MenuGroup = { id: string; title: string; minSelected: number; maxSelected: number; options: MenuOption[] };
export type MenuItem = {
  id: string;
  categoryId: string;
  title: string;
  basePrice: string;
  available: boolean;
  lowStockFlag: boolean;
  modifierGroups: MenuGroup[];
};
export type MenuCategory = { id: string; title: string; items: MenuItem[] };
export type Catalog = { revision: number; currency: string; categories: MenuCategory[] };

export type Modifier = { optionId: string; groupTitle: string; title: string; priceDelta: string };
export type Line = {
  lineNo: number;
  menuItemId: string;
  title: string;
  quantity: number;
  unitBasePrice: string;
  unitPrice: string;
  lineTotal: string;
  modifiers: Modifier[];
};
export type Quote = {
  quoteId: string;
  quoteRef: string;
  currency: string;
  lines: Line[];
  total: string;
  createdAt: string;
  expiresAt: string;
};
export type Order = {
  id: string;
  humanNo: number;
  quoteId: string;
  quoteRef: string;
  recordStatus: "TRAINING" | "RECORDED" | "EXCEPTION";
  prepStatus: PrepStatus;
  version: number;
  total: string;
  currency: string;
  placedAt: string;
  lines: Line[];
  payment: { method: string; amount: string; externalReference: string | null; recordedAt: string; verification: string };
  corrections: {
    id: string;
    type: string;
    externalActionState: string;
    confirmsCorrectionId: string | null;
    reason: string;
    createdAt: string;
  }[];
};

export type PrepStatus = "NEW" | "PREPARING" | "READY" | "CLEARED" | "CANCELLED";
export type Ticket = {
  orderId: string;
  humanNo: number;
  prepStatus: PrepStatus;
  version: number;
  placedAt: string;
  lines: { lineNo: number; title: string; quantity: number; modifiers: { groupTitle: string; title: string }[] }[];
};
