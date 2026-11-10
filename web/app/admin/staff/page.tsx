"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { AdminShell, Notice } from "@/components/AdminShell";
import { errorText, isStale, temporaryCredential, type Staff } from "@/lib/admin";
import { api, type Role } from "@/lib/api";
import { useT, useTr } from "@/lib/i18n";
import { useMe } from "@/lib/session";

const ROLES: Role[] = ["CASHIER", "BARISTA", "ADMIN"];

export default function StaffPage() {
  const tr = useTr();
  const t = useT();
  const me = useMe(["ADMIN"]);
  const [staff, setStaff] = useState<Staff[] | null>(null);
  const [notice, setNotice] = useState<{ kind: "error" | "ok" | "warn"; text: string } | null>(null);
  // the temporary credential is shown once, then gone
  const [shown, setShown] = useState<{ login: string; credential: string } | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => api.get<Staff[]>("/admin/staff").then(setStaff), []);

  useEffect(() => {
    if (me) load().catch((e) => setNotice({ kind: "error", text: errorText(e, t("offline")) }));
  }, [me, load, t]);

  async function run(action: () => Promise<unknown>, ok: string) {
    setBusy(true);
    setNotice(null);
    try {
      await action();
      await load();
      setNotice({ kind: "ok", text: ok });
      return true;
    } catch (e) {
      await load().catch(() => undefined);
      setNotice({
        kind: isStale(e) ? "warn" : "error",
        text: isStale(e) ? tr("Запись изменилась, данные обновлены. Повторите.", "The record changed and was reloaded. Repeat.") : errorText(e, t("offline")),
      });
      return false;
    } finally {
      setBusy(false);
    }
  }

  const roleLabel = (r: Role) => (r === "CASHIER" ? tr("Кассир", "Cashier") : r === "BARISTA" ? tr("Бариста", "Barista") : tr("Админ", "Admin"));

  return (
    <AdminShell me={me} title={tr("Персонал", "Staff")}>
      <div className="flex flex-col gap-4">
        {notice && <Notice kind={notice.kind}>{notice.text}</Notice>}
        {shown && (
          <Notice kind="warn">
            <p>
              {tr("Временный PIN/пароль для", "Temporary credential for")} <b>{shown.login}</b>{" "}
              {tr("(показывается один раз, передайте сотруднику лично):", "(shown once; hand it over in person):")}
            </p>
            <p className="my-2 font-mono text-3xl font-bold tracking-widest">{shown.credential}</p>
            <p className="text-sm">{tr("При первом входе сотрудник обязан его сменить.", "The person must change it at first sign-in.")}</p>
            <button type="button" className="btn-secondary mt-2" onClick={() => setShown(null)}>{tr("Скрыть", "Hide")}</button>
          </Notice>
        )}
        <CreateForm busy={busy} roleLabel={roleLabel} onCreate={async (login, displayName, role) => {
          const credential = temporaryCredential(role);
          const ok = await run(() => api.post("/admin/staff", { login, displayName, role, temporaryCredential: credential }),
            tr("Сотрудник создан.", "Staff member created."));
          if (ok) setShown({ login, credential });
          return ok;
        }} />
        {staff && (
          <div className="card overflow-x-auto">
            <table className="w-full [&_td]:px-2 [&_th]:px-2 min-w-[44rem] text-sm">
              <thead className="text-left text-stone-500">
                <tr>
                  <th className="py-1">{tr("Логин", "Login")}</th>
                  <th>{tr("Имя", "Name")}</th>
                  <th>{tr("Роль", "Role")}</th>
                  <th>{tr("Статус", "Status")}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {staff.map((s) => (
                  <StaffRow key={`${s.id}:${s.revision}`} staff={s} self={s.id === me?.id} busy={busy} roleLabel={roleLabel}
                            onPatch={(body) => run(() => api.patch(`/admin/staff/${s.id}`, { ...body, expectedRevision: s.revision }),
                              tr("Сохранено.", "Saved."))}
                            onReset={async () => {
                              const credential = temporaryCredential(s.role);
                              const ok = await run(() => api.post(`/admin/staff/${s.id}/credential-reset`, { temporaryCredential: credential }),
                                tr("PIN/пароль сброшен, сессии сотрудника закрыты.", "Credential reset; the person's sessions ended."));
                              if (ok) setShown({ login: s.login, credential });
                            }} />
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-sm text-stone-600">
          {tr("Последнего активного админа нельзя отключить или понизить. Сброс PIN — единственный способ разблокировать учётную запись.",
            "The last active admin cannot be disabled or demoted. A credential reset is the only way to unlock an account.")}
        </p>
      </div>
    </AdminShell>
  );
}

function CreateForm({ busy, roleLabel, onCreate }: {
  busy: boolean;
  roleLabel: (r: Role) => string;
  onCreate: (login: string, displayName: string, role: Role) => Promise<boolean>;
}) {
  const tr = useTr();
  const [login, setLogin] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<Role>("CASHIER");
  const validLogin = /^[A-Za-z0-9._-]{1,64}$/.test(login.trim());

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (await onCreate(login.trim(), name.trim(), role)) {
      setLogin("");
      setName("");
    }
  }

  return (
    <form onSubmit={submit} className="card flex flex-wrap items-end gap-2">
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{tr("Логин (латиница, цифры)", "Login (letters, digits)")}</span>
        <input className="field w-48" value={login} onChange={(e) => setLogin(e.target.value)} maxLength={64} required />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{tr("Имя", "Display name")}</span>
        <input className="field w-56" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} required />
      </label>
      <label className="flex flex-col gap-1">
        <span className="text-sm font-medium">{tr("Роль", "Role")}</span>
        <select className="field w-40" value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
        </select>
      </label>
      <button className="btn-primary" disabled={busy || !validLogin || !name.trim()}>{tr("Создать", "Create")}</button>
    </form>
  );
}

function StaffRow({ staff, self, busy, roleLabel, onPatch, onReset }: {
  staff: Staff;
  self: boolean;
  busy: boolean;
  roleLabel: (r: Role) => string;
  onPatch: (body: object) => Promise<boolean>;
  onReset: () => Promise<void>;
}) {
  const tr = useTr();
  const [name, setName] = useState(staff.displayName);
  const [role, setRole] = useState<Role>(staff.role);
  const dirty = name.trim() !== staff.displayName || role !== staff.role;
  return (
    <tr className={`border-t border-stone-100 ${staff.active ? "" : "opacity-60"}`}>
      <td className="py-2 font-mono">{staff.login}{self && <span className="ml-1 text-xs text-stone-500">({tr("вы", "you")})</span>}</td>
      <td><input className="field w-48" value={name} onChange={(e) => setName(e.target.value)} maxLength={120} /></td>
      <td>
        <select className="field w-36" value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => <option key={r} value={r}>{roleLabel(r)}</option>)}
        </select>
      </td>
      <td className="text-xs">
        <span className={staff.active ? "font-semibold text-emerald-700" : "font-semibold text-stone-500"}>
          {staff.active ? tr("активен", "active") : tr("отключён", "disabled")}
        </span>
        {staff.locked && <span className="ml-1 font-semibold text-rose-700">· {tr("заблокирован", "locked")}</span>}
        {staff.mustChangeCredential && <span className="ml-1 text-amber-700">· {tr("ждёт смены PIN", "must change PIN")}</span>}
      </td>
      <td>
        <div className="flex flex-wrap justify-end gap-1">
          <button type="button" className="btn-secondary" disabled={busy || !dirty || !name.trim()}
                  onClick={() => void onPatch({ displayName: name.trim(), role })}>
            {tr("Сохранить", "Save")}
          </button>
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => void onPatch({ active: !staff.active })}>
            {staff.active ? tr("Отключить", "Disable") : tr("Включить", "Enable")}
          </button>
          <button type="button" className="btn-secondary" disabled={busy} onClick={() => void onReset()}>
            {tr("Сбросить PIN", "Reset credential")}
          </button>
        </div>
      </td>
    </tr>
  );
}
