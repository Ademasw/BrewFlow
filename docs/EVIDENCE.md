# Evidence — 2026-11-02 (section 13: command, commit, result)

Commit: none. The folder is not a git repository yet, so results are tied to the working tree of 2026-11-02.
Machine: Windows 11, Temurin 21.0.11, PostgreSQL 18.0 local cluster (`scripts/dev-db.sh`), Node 22.19.0.
Mode: `APP_MODE=TRAINING`, except `LiveModeMoneyTest`, which runs `LIVE` with a test-only SOP id.
Fiscal choice DEC-03 is **OPEN**: nothing here approves live sales.

## Backend: `cd backend && ./mvnw package` → BUILD SUCCESS, 68 tests, 0 failures, 0 errors, 0 skipped

| Class | Tests | Covers |
|---|---|---|
| AppModeTest | 6 | section 3 fail-closed startup; T016; the command-mode context used by bootstrap-admin starts |
| BootstrapAdminTest | 4 | T041: one admin only; refuses a short password, a file other users can read, missing config; writes nothing on refusal |
| AuthAndStaffTest | 17 | BF-S1-03, BF-S1-06, T042: CSRF, 401/403 by role, session rotation on login and switch, logout kills the session, forced first change, lockout and audited reset, IP throttle, deactivation ends sessions, LAST_ACTIVE_ADMIN, VERSION_CONFLICT, no credentials in logs or audit |
| QuoteAndOrderTest | 18 | BF-S2-01..07: 20 fixtures to the cent with no tax field, client totals ignored, modifier rules, ATTESTATION_REQUIRED, SHIFT_CLOSED, MODE_REJECTED, same-key replay, IDEMPOTENCY_KEY_REUSED, QUOTE_ALREADY_CONSUMED (orderId only for the creator), QUOTE_EXPIRED, **20 parallel same-key posts → 1 order, 1 payment**, 20 parallel keys → 1 order, recovery by key, ownership 404, snapshots survive a price edit, append-only triggers, MENU_CHANGED, availability toggle |
| KitchenTest | 9 | BF-S3-01..05, T021: no tender fields, section 4.5 matrix, replay without double increment, CANCELLED by PATCH (barista 403, admin 409 with `details.use`), SSE after commit only, rolled-back submit emits nothing, logout and deactivation close the stream, **50 connect/disconnect cycles leave no emitter** |
| OpenApiDocsTest | 3 | Swagger UI and `/v3/api-docs` served (version 0.3.2-draft, Idempotency-Key, error envelope, csrf scheme); docs do not open the API |
| LiveModeMoneyTest | 11 | admin day list with void state (admin only) and `businessDate` in /auth/me;  T028 (cash 520.00 / card 200.00 / total 720.00 / pending 1 / variance −20.00 → after confirming: 420.00 / 620.00 / 0 / 80.00), T025, T043, T011, T044, T016 |

## Real `bootstrap-admin` command (jar, dev database)

| Run | Result |
|---|---|
| Password file with inherited Windows ACL | exit 3 "password file is readable by other users" |
| After `icacls /inheritance:r /grant:r %USERNAME%:R` | exit 0 "admin 'owner' created; delete the password file now" |
| Second run | exit 5 "an ADMIN account already exists; nothing was written" |

## Web: `cd web && npx tsc --noEmit && npm run lint && npm run build` → all passed (9 routes)

## Manual browser walkthrough (built-in browser, production build, TRAINING)

Cashier login → POS → Latte with required size M, oat, vanilla (provisional 300.50) → server quote 300.50 with
quoteRef → only the TRAINING method offered → record button disabled until the attestation tick → order #1
"Recorded by staff, not verified" → sign-out, barista login → KDS shows #1 with modifiers and no prices → order #2
placed by another client appeared without a reload (SSE through the Next.js proxy) → #1 NEW → PREPARING → READY →
CLEARED, then left the board.

## Not run, not claimed

Playwright suites, the p95 latency log (BF-S3-06), restore drill (BF-S5-04), CI on GitHub, UAT.

## Admin screens (built-in browser, production build, TRAINING)

Admin login lands on /admin/shifts with the open-shift report (training payments excluded from totals). Orders list for
the business date → order #4 (a test order) → pending void → "confirm pending void" → status CANCELLED, both notes
kept, form replaced by "already voided". Menu, staff, recovery and audit screens render. Every screen makes 2–3 API
requests after load (checked with the Performance API).

Bug found and fixed during this check: the translation functions `t`/`tr` were new on every render and listed in
effect dependencies, so POS, order, and all admin screens re-fetched in a loop. They are now stable per locale.
