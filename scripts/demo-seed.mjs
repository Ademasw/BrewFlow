// Seeds a local TRAINING demo through the public API only (no SQL): admin first-credential change, a small
// menu with modifiers, one cashier, one barista, an open shift. Run after `bootstrap-admin`.
//   node scripts/demo-seed.mjs
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const envFile = fileURLToPath(new URL("./demo.env", import.meta.url));
const env = Object.fromEntries(
  readFileSync(envFile, "utf8")
    .split(/\r?\n/)
    .filter((l) => l && !l.startsWith("#"))
    .map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const base = process.env.BREWFLOW_URL ?? env.BREWFLOW_URL;

class Client {
  cookie = "";
  csrf = null;

  async call(method, path, body) {
    const headers = { Accept: "application/json" };
    if (method !== "GET") {
      // fetched first: it may start the session whose cookie goes on this request
      if (!this.csrf) this.csrf = await this.call("GET", "/auth/csrf");
      headers[this.csrf.headerName] = this.csrf.token;
    }
    if (this.cookie) headers.Cookie = this.cookie;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const res = await fetch(`${base}/api/v1${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    for (const c of res.headers.getSetCookie()) {
      if (c.startsWith("BFSESSION=")) this.cookie = c.split(";")[0];
    }
    const text = await res.text();
    const json = text ? JSON.parse(text) : undefined;
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
    return json;
  }

  async login(login, credential) {
    const me = await this.call("POST", "/auth/login", { login, credential });
    this.csrf = me.csrf;
    return me;
  }
}

async function signInChanging(login, temporary, final) {
  const c = new Client();
  try {
    const me = await c.login(login, temporary);
    if (me.mustChangeCredential) {
      await c.call("POST", "/auth/credential", { currentCredential: temporary, newCredential: final });
    }
  } catch {
    await c.login(login, final);
  }
  return c;
}

const admin = await signInChanging(env.DEMO_ADMIN_LOGIN, env.DEMO_ADMIN_BOOTSTRAP_PASSWORD, env.DEMO_ADMIN_PASSWORD);

const menu = await admin.call("GET", "/admin/menu");
if (menu.categories.length === 0) {
  const id = async (path, body) => (await admin.call("POST", path, body)).id;
  const coffee = await id("/admin/menu/categories", { title: "Кофе", sortOrder: 1 });
  const tea = await id("/admin/menu/categories", { title: "Чай", sortOrder: 2 });
  const food = await id("/admin/menu/categories", { title: "Выпечка", sortOrder: 3 });

  const size = await id("/admin/menu/modifier-groups", { title: "Размер", minSelected: 1, maxSelected: 1 });
  for (const [title, priceDelta] of [["S", "0.00"], ["M", "40.00"], ["L", "70.00"]]) {
    await id("/admin/menu/modifier-options", { groupId: size, title, priceDelta });
  }
  const milk = await id("/admin/menu/modifier-groups", { title: "Молоко", minSelected: 0, maxSelected: 1 });
  for (const [title, priceDelta] of [["Овсяное", "45.50"], ["Миндальное", "55.25"], ["Безлактозное", "30.00"]]) {
    await id("/admin/menu/modifier-options", { groupId: milk, title, priceDelta });
  }
  const syrup = await id("/admin/menu/modifier-groups", { title: "Сироп", minSelected: 0, maxSelected: 2 });
  for (const [title, priceDelta] of [["Ваниль", "25.00"], ["Карамель", "25.00"], ["Лесной орех", "27.50"]]) {
    await id("/admin/menu/modifier-options", { groupId: syrup, title, priceDelta });
  }

  const items = [
    [coffee, "Эспрессо", "120.00", [syrup]],
    [coffee, "Американо", "140.00", [milk, syrup]],
    [coffee, "Капучино", "180.00", [size, milk, syrup]],
    [coffee, "Латте", "190.00", [size, milk, syrup]],
    [coffee, "Раф", "230.00", [size, milk, syrup]],
    [tea, "Чёрный чай", "90.00", []],
    [tea, "Матча латте", "250.00", [size, milk]],
    [food, "Круассан", "110.00", []],
    [food, "Чизкейк", "260.00", []],
  ];
  for (const [categoryId, title, basePrice, groups] of items) {
    const itemId = await id("/admin/menu/items", { categoryId, title, basePrice });
    if (groups.length) {
      await admin.call("PUT", `/admin/menu/items/${itemId}/modifier-groups`, { groupIds: groups, expectedRevision: 0 });
    }
  }
  console.log("menu created");
}

const staff = await admin.call("GET", "/admin/staff");
for (const [login, name, role, temp] of [
  [env.DEMO_CASHIER_LOGIN, "Аида (касса)", "CASHIER", env.DEMO_CASHIER_TEMP_PIN],
  [env.DEMO_BARISTA_LOGIN, "Тимур (бариста)", "BARISTA", env.DEMO_BARISTA_TEMP_PIN],
]) {
  if (!staff.some((s) => s.login === login)) {
    await admin.call("POST", "/admin/staff", { login, displayName: name, role, temporaryCredential: temp });
    console.log(`staff ${login} created`);
  }
}
await signInChanging(env.DEMO_CASHIER_LOGIN, env.DEMO_CASHIER_TEMP_PIN, env.DEMO_CASHIER_PIN);
await signInChanging(env.DEMO_BARISTA_LOGIN, env.DEMO_BARISTA_TEMP_PIN, env.DEMO_BARISTA_PIN);

const shifts = await admin.call("GET", "/admin/shifts");
if (!shifts.some((s) => s.status === "OPEN")) {
  await admin.call("POST", "/admin/shifts/open");
  console.log("shift opened");
}
console.log("demo ready: see scripts/demo.env for the local demo logins");
