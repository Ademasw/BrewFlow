# BrewFlow v3.2 — реализация (S1–S4)

Монорепозиторий по ТЗ «BrewFlow v3.2 technical specification (CANDIDATE)»: POS для кассы, кухонный экран (KDS) и
админка для одного кафе. Работа в режиме `TRAINING`. LIVE-режим реализован и покрыт тестами, но для настоящих
продаж нужна подпись владельца (DEC-03); код этого решения не заменяет.

```
backend/   Spring Boot 4.1.2 · Java 21 · PostgreSQL 18 · Flyway V1–V4 · Maven Wrapper
web/       Next.js 16.4.3 (App Router) · React 19 · TypeScript · Tailwind 4
scripts/   dev-db.sh (локальный PostgreSQL 18), demo-seed.mjs + demo.env (демо-данные через API)
docs/      adr/ADR-013-versions.md, EVIDENCE.md (команды и результаты)
.github/   workflows/ci.yml (lint/test/build/secret scan; ещё не запускался — нет удалённого репозитория)
```

## Что сделано

| Спринт | Сделано | Не сделано |
|---|---|---|
| **S1 auth и каркас** | S1-02 Flyway + PG18; S1-03 именные учётки, `bootstrap-admin`, `/admin/staff`, пароль админа ≥12, PIN 6 цифр, обязательная смена при первом входе, сессия, CSRF, троттлинг и блокировка; S1-04 экраны входа и смены PIN, маршруты по ролям; S1-05 20 эталонных заказов (в тестах; пометка «не валидировано владельцем»); S1-06 request-id, очищенные логи, health/readiness, конверт ошибок; S1-07 `docs/EVIDENCE.md` | S1-01: CI описан, но не запускался; Playwright-смоук |
| **S2 котировка и заказ** | S2-01 меню и правила модификаторов; S2-02 POS-сетка и корзина; S2-03 серверная котировка, снапшот, quoteRef, TTL 10 мин; S2-04 экран подтверждения (в TRAINING скрыты живые методы, в LIVE — TRAINING); S2-05 идемпотентный `POST /orders`; S2-06 ключ переживает таймаут и перезагрузку; S2-07 контрактные тесты | Playwright-тест «обрыв ответа» в браузере |
| **S3 кухня** | S3-01 снимок открытых тикетов и кухонная проекция без сумм; S3-02 матрица 4.5 и `prep_version`; S3-03 SSE после коммита, heartbeat, очистка; S3-04 доска со статусом текстом и цветом; S3-05 повторный снимок при фокусе, переподключении и каждом событии | S3-06 замер p95; S3-07 Playwright-сценарий (пройден вручную в браузере) |
| **S4 админ, отмены, восстановление, смены** | S4-01 API категорий, позиций, групп, опций, персонала; S4-03 VOID_NOTE с подтверждением, поиск просроченных котировок, восстановление, исключения; S4-04 смены и отчёт по формуле раздела 5; S4-06 флаг доступности; **S4-02 и S4-05 админ-экраны**: `/admin/shifts`, `/admin/reports`, `/admin/orders` и `/admin/orders/{id}/correct`, `/admin/recover-quote`, `/admin/menu`, `/admin/staff`, `/admin/audit` | S4-07 прогон с владельцем |
| **S5, S6** | — | восстановление из бэкапа, UAT, деплой, Go/No-Go (это работа владельца и эксплуатации) |

Блокирующие релиз тесты раздела 11, покрытые автотестами: **T011, T016, T021, T025, T028, T041, T042, T043, T044**.

## Запуск

Нужны Java 21, Node.js ≥ 20.9 (ТЗ: 24 LTS) и установленный PostgreSQL 18 (Docker не нужен).

```bash
bash scripts/dev-db.sh start
```

```bash
cd backend && ./mvnw test
```

```bash
cd backend && ./mvnw package -DskipTests
```

Первый админ создаётся только командой (HTTP-маршрута для этого нет). Файл пароля должен быть доступен **только
владельцу**: на Windows `icacls <файл> /inheritance:r /grant:r "%USERNAME%:R"`, на Linux `chmod 600`. После
использования файл нужно удалить.

```bash
BOOTSTRAP_ADMIN_LOGIN=owner BOOTSTRAP_ADMIN_PASSWORD_FILE=/path/to/file APP_MODE=TRAINING java -jar backend/target/brewflow-backend-0.3.2-SNAPSHOT.jar bootstrap-admin
```

```bash
APP_MODE=TRAINING COOKIE_SECURE=false java -jar backend/target/brewflow-backend-0.3.2-SNAPSHOT.jar
```

```bash
node scripts/demo-seed.mjs
```

```bash
cd web && npm ci && npm run build && npm run start
```

Откройте http://localhost:3000. Демо-логины (только для локальной базы) лежат в `scripts/demo.env`. Кассир попадает
на `/pos`, бариста на `/kds`.

## Swagger

- Swagger UI: http://localhost:8080/swagger-ui.html; документ: `/v3/api-docs` (JSON) и `/v3/api-docs.yaml`.
- Снимок контракта лежит в [docs/openapi/OpenAPI_v1_CANDIDATE.yaml](docs/openapi/OpenAPI_v1_CANDIDATE.yaml)
  (версия `0.3.2-draft`, как в разделе 7). После изменения API файл нужно обновить в той же правке:
  `curl -s http://127.0.0.1:8080/v3/api-docs.yaml -o docs/openapi/OpenAPI_v1_CANDIDATE.yaml`.
- Как пользоваться «Try it out»: `GET /auth/csrf` → **Authorize → csrf** → `POST /auth/login` → вставить новый
  `csrf.token` из ответа в **Authorize**. Cookie сессии браузер отправляет сам. Подробно — в шапке Swagger UI.
- Swagger открыт без входа, но API за ним по-прежнему требует сессию и роль. На production выключается через
  `SWAGGER_ENABLED=false`.

`APP_MODE` обязателен: без него или с ошибкой в значении приложение не стартует. `APP_MODE=LIVE` дополнительно требует
`FISCAL_SOP_APPROVED=true` и непустой `FISCAL_SOP_ID`. `COOKIE_SECURE=false` нужен только для http на localhost.

## Отступления от ТЗ (честно)

1. **Testcontainers → локальный PostgreSQL 18.** На машине нет Docker. Все интеграционные тесты всё равно идут на
   настоящем PostgreSQL 18 (`brewflow_test` на `127.0.0.1:55432`); в CI — сервис-контейнер `postgres:18`.
2. **Node 22 локально** вместо 24 LTS (Next 16 требует ≥ 20.9; в CI указан 24).
3. Троттлинг входа — по аккаунту (5 неудач → блокировка до сброса админом) и по IP (30 неудач за 10 мин).
   Отдельного лимита «по устройству» нет.
4. Исходного `03_Technical/OpenAPI_v1_CANDIDATE.yaml` в присланном пакете не было, поэтому контракт сгенерирован
   из кода (springdoc) в `docs/openapi/OpenAPI_v1_CANDIDATE.yaml`.
5. Решения, которые ТЗ оставило открытыми, приняты так (их стоит согласовать):
   - закрыть исключение без заказа: `POST /admin/quotes/exceptions/{id}/resolve-outside` (в таблице маршрутов ТЗ его нет);
   - прямой CONFIRMED-void на заказе с открытым PENDING автоматически ссылается на этот PENDING, чтобы счётчик ожидающих упал;
   - повторное открытие уже открытой смены и повторное закрытие закрытой отвечают `409 INVALID_TRANSITION`;
   - отчёт по одной смене: `GET /admin/reports/daily?shiftId=…`;
   - список заказов за день для админки: `GET /admin/orders?businessDate=…` (в таблице маршрутов ТЗ его нет, но без
     него админу не из чего выбрать заказ для отмены);
   - `GET /auth/me` возвращает `businessDate`: бизнес-дату по часовому поясу кафе считает сервер, а не браузер.
6. Временный PIN/пароль при создании сотрудника и сбросе генерирует браузер админа и показывает его один раз.
